package SpinalRiscv.Plugin

import SpinalRiscv._
import spinal.core._
import spinal.lib._


case class InstructionCacheConfig( cacheSize : Int,
                                   bytePerLine : Int,
                                   wayCount : Int,
                                   wrappedMemAccess : Boolean,
                                   addressWidth : Int,
                                   cpuDataWidth : Int,
                                   memDataWidth : Int,
                                   catchAccessFault : Boolean,
                                   asyncTagMemory : Boolean,
                                   twoStageLogic : Boolean){
  def burstSize = bytePerLine*8/memDataWidth
}



class IBusCachedPlugin(config : InstructionCacheConfig) extends Plugin[VexRiscv]{
  import config._
  var iBus  : InstructionCacheMemBus = null

  object IBUS_ACCESS_ERROR extends Stageable(Bool)
  var decodeExceptionPort : Flow[ExceptionCause] = null
  override def setup(pipeline: VexRiscv): Unit = {
    pipeline.unremovableStages += pipeline.prefetch

    if(catchAccessFault) {
      val exceptionService = pipeline.service(classOf[ExceptionService])
      decodeExceptionPort = exceptionService.newExceptionPort(pipeline.decode,1)
    }
  }

  override def build(pipeline: VexRiscv): Unit = {
    import pipeline._
    import pipeline.config._

    val cache = new InstructionCache(this.config)
    iBus = master(new InstructionCacheMemBus(this.config)).setName("iBus")
    iBus <> cache.io.mem


    //Connect prefetch cache side
    cache.io.cpu.prefetch.isValid := prefetch.arbitration.isValid
    cache.io.cpu.prefetch.isFiring := prefetch.arbitration.isFiring
    cache.io.cpu.prefetch.address := prefetch.output(PC)
    prefetch.arbitration.haltIt setWhen(cache.io.cpu.prefetch.haltIt)

    //Connect fetch cache side
    cache.io.cpu.fetch.isValid  := fetch.arbitration.isValid
    cache.io.cpu.fetch.isStuck  := fetch.arbitration.isStuck
    if(!twoStageLogic) cache.io.cpu.fetch.isStuckByOthers  := fetch.arbitration.isStuckByOthers
    cache.io.cpu.fetch.address  := fetch.output(PC)
    if(!twoStageLogic) fetch.arbitration.haltIt setWhen(cache.io.cpu.fetch.haltIt)
    if(!twoStageLogic) fetch.insert(INSTRUCTION) := cache.io.cpu.fetch.data

    cache.io.flush.cmd.valid := False

    if(twoStageLogic){
      cache.io.cpu.decode.isValid := decode.arbitration.isValid
      decode.arbitration.haltIt.setWhen(cache.io.cpu.decode.haltIt)
      cache.io.cpu.decode.isStuck := decode.arbitration.isStuck
      cache.io.cpu.decode.address := decode.input(PC)
      decode.insert(INSTRUCTION)  := cache.io.cpu.decode.data
    }


    if(catchAccessFault){
      if(!twoStageLogic) fetch.insert(IBUS_ACCESS_ERROR) := cache.io.cpu.fetch.error

      decodeExceptionPort.valid   := decode.arbitration.isValid && decode.input(IBUS_ACCESS_ERROR)
      decodeExceptionPort.code    := 1
      decodeExceptionPort.badAddr := decode.input(PC)
    }
  }
}



case class InstructionCacheCpuPrefetch(p : InstructionCacheConfig) extends Bundle with IMasterSlave{
  val isValid    = Bool
  val isFiring = Bool
  val haltIt   = Bool
  val address  = UInt(p.addressWidth bit)

  override def asMaster(): Unit = {
    out(isValid, isFiring, address)
    in(haltIt)
  }
}

case class InstructionCacheCpuFetch(p : InstructionCacheConfig) extends Bundle with IMasterSlave {
  val isValid = Bool
  val haltIt  = if(!p.twoStageLogic) Bool else null
  val isStuck = Bool
  val isStuckByOthers = if(!p.twoStageLogic) Bool else null
  val address = UInt(p.addressWidth bit)
  val data    = if(!p.twoStageLogic) Bits(32 bit) else null
  val error   = if(!p.twoStageLogic && p.catchAccessFault) Bool else null

  override def asMaster(): Unit = {
    out(isValid, isStuck, address)
    outWithNull(isStuckByOthers)
    inWithNull(error,data,haltIt)
  }
}

case class InstructionCacheCpuDecode(p : InstructionCacheConfig) extends Bundle with IMasterSlave {
  require(p.twoStageLogic)
  val isValid = Bool
  val haltIt  = Bool
  val isStuck = Bool
  val address = UInt(p.addressWidth bit)
  val data    = Bits(32 bit)
  val error   = if(p.catchAccessFault) Bool else null

  override def asMaster(): Unit = {
    out(isValid, isStuck, address)
    in(haltIt, data)
    if(p.catchAccessFault) in(error)
  }
}

case class InstructionCacheCpuBus(p : InstructionCacheConfig) extends Bundle with IMasterSlave{
  val prefetch = InstructionCacheCpuPrefetch(p)
  val fetch = InstructionCacheCpuFetch(p)
  val decode = if(p.twoStageLogic) InstructionCacheCpuDecode(p) else null

  override def asMaster(): Unit = {
    master(prefetch)
    master(fetch)
    if(p.twoStageLogic) master(decode)
  }
}

case class InstructionCacheTranslationBus(p : InstructionCacheConfig) extends Bundle with IMasterSlave{
  val virtualAddress  = UInt(32 bits)
  val physicalAddress = UInt(32 bits)
  val error           = if(p.catchAccessFault) Bool else null

  override def asMaster(): Unit = {
    out(virtualAddress)
    in(physicalAddress)
    if(p.catchAccessFault) in(error)
  }
}

case class InstructionCacheMemCmd(p : InstructionCacheConfig) extends Bundle{
  val address = UInt(p.addressWidth bit)
}
case class InstructionCacheMemRsp(p : InstructionCacheConfig) extends Bundle{
  val data = Bits(32 bit)
  val error = if(p.catchAccessFault) Bool else null
}

case class InstructionCacheMemBus(p : InstructionCacheConfig) extends Bundle with IMasterSlave{
  val cmd = Stream (InstructionCacheMemCmd(p))
  val rsp = Flow (InstructionCacheMemRsp(p))

  override def asMaster(): Unit = {
    master(cmd)
    slave(rsp)
  }
}

case class InstructionCacheFlushBus() extends Bundle with IMasterSlave{
  val cmd = Event
  val rsp = Bool

  override def asMaster(): Unit = {
    master(cmd)
    in(rsp)
  }
}

class InstructionCache(p : InstructionCacheConfig) extends Component{
  import p._
  assert(wayCount == 1)
  assert(cpuDataWidth == memDataWidth)
  val io = new Bundle{
    val flush = slave(InstructionCacheFlushBus())
//    val translator = master(InstructionCacheTranslationBus(p))
    val cpu = slave(InstructionCacheCpuBus(p))
    val mem = master(InstructionCacheMemBus(p))
  }
//  val haltCpu = False
  val lineWidth = bytePerLine*8
  val lineCount = cacheSize/bytePerLine
  val wordWidth = Math.max(memDataWidth,32)
  val wordWidthLog2 = log2Up(wordWidth)
  val wordPerLine = lineWidth/wordWidth
  val bytePerWord = wordWidth/8
  val wayLineCount = lineCount/wayCount
  val wayLineLog2 = log2Up(wayLineCount)
  val wayWordCount = wayLineCount * wordPerLine

  val tagRange = addressWidth-1 downto log2Up(wayLineCount*bytePerLine)
  val lineRange = tagRange.low-1 downto log2Up(bytePerLine)
  val wordRange = log2Up(bytePerLine)-1 downto log2Up(bytePerWord)
  val tagLineRange = tagRange.high downto lineRange.low

  class LineInfo extends Bundle{
    val valid = Bool
    val error = if(catchAccessFault) Bool else null
    val address = UInt(tagRange.length bit)
  }


  val ways = Array.fill(wayCount)(new Area{
    val tags = Mem(new LineInfo(),wayLineCount)
    val datas = Mem(Bits(wordWidth bits),wayWordCount)
  })


  io.cpu.prefetch.haltIt := False

  val lineLoader = new Area{
    val requestIn = Stream(wrap(new Bundle{
      val addr = UInt(addressWidth bits)
    }))




    val flushCounter = Reg(UInt(log2Up(wayLineCount) + 1 bit)) init(0)
    when(!flushCounter.msb){
      io.cpu.prefetch.haltIt := True
      flushCounter := flushCounter + 1
    }
    when(!RegNext(flushCounter.msb)){
      io.cpu.prefetch.haltIt := True
    }
    val flushFromInterface = RegInit(False)
    when(io.flush.cmd.valid){
      io.cpu.prefetch.haltIt := True
      when(io.flush.cmd.ready){
        flushCounter := 0
        flushFromInterface := True
      }
    }

    io.flush.rsp := flushCounter.msb.rise && flushFromInterface

    val loadingWithErrorReg = if(catchAccessFault) RegInit(False) else null
    val loadingWithError    = if(catchAccessFault) loadingWithErrorReg else null
    if(catchAccessFault) loadingWithErrorReg := loadingWithError



    val request = requestIn.stage()

    val lineInfoWrite = new LineInfo()
    lineInfoWrite.valid := flushCounter.msb
    lineInfoWrite.address := request.addr(tagRange)
    if(catchAccessFault) lineInfoWrite.error := loadingWithError

    //Send memory requests
    val memCmdSended = RegInit(False) setWhen(io.mem.cmd.fire)
    io.mem.cmd.valid := request.valid && !memCmdSended
    if(wrappedMemAccess)
      io.mem.cmd.address := request.addr(tagRange.high downto wordRange.low) @@ U(0,wordRange.low bit)
    else
      io.mem.cmd.address := request.addr(tagRange.high downto lineRange.low) @@ U(0,lineRange.low bit)

    val wordIndex = Reg(UInt(log2Up(wordPerLine) bit))
    val loadedWordsNext = Bits(wordPerLine bit)
    val loadedWords = RegNext(loadedWordsNext)
    val loadedWordsReadable = RegNext(loadedWords)
    loadedWordsNext := loadedWords

    val waysWritePort = ways(0).datas.writePort //Not multi ways
    waysWritePort.valid   := io.mem.rsp.valid
    waysWritePort.address := request.addr(lineRange) @@ wordIndex
    waysWritePort.data    := io.mem.rsp.data
    when(io.mem.rsp.valid){
      wordIndex := wordIndex + 1
      loadedWordsNext(wordIndex) := True
      if(catchAccessFault) loadingWithError setWhen io.mem.rsp.error
    }

    val memRspLast = loadedWordsNext === B(loadedWordsNext.range -> true)

    val readyDelay = Reg(UInt(1 bit))
    when(memRspLast){
      readyDelay := readyDelay + 1
    }
    request.ready := readyDelay === 1


    when((request.valid && memRspLast) || !flushCounter.msb){
      val tagsAddress = Mux(flushCounter.msb,request.addr(lineRange),flushCounter(flushCounter.high-1 downto 0))
      ways(0).tags(tagsAddress) := lineInfoWrite  //TODO
    }

    when(requestIn.ready){
      memCmdSended := False
      wordIndex := requestIn.addr(wordRange)
      loadedWords := 0
      loadedWordsReadable := 0
      readyDelay := 0
      if(catchAccessFault) loadingWithErrorReg := False
    }
  }

  val task = if(!twoStageLogic) new Area{
    val waysHitValid = False
    val waysHitError = Bool.assignDontCare()
    val waysHitWord = Bits(wordWidth bit)

    val waysRead = for(way <- ways) yield new Area{
      val readAddress = Mux(io.cpu.fetch.isStuck,io.cpu.fetch.address,io.cpu.prefetch.address) //TODO FMAX
      val tag = if(asyncTagMemory)
        way.tags.readAsync(io.cpu.fetch.address(lineRange))
      else
        way.tags.readSync(readAddress(lineRange))

      val data = way.datas.readSync(readAddress(lineRange.high downto wordRange.low))
      waysHitWord := data //Not applicable to multi way
      when(tag.valid && tag.address === io.cpu.fetch.address(tagRange)) {
        waysHitValid := True
        if(catchAccessFault) waysHitError := tag.error
      }

      when(lineLoader.request.valid && lineLoader.request.addr(lineRange) === io.cpu.fetch.address(lineRange)){
        waysHitValid := False //Not applicable to multi way
      }
    }


    val loaderHitValid = lineLoader.request.valid && lineLoader.request.addr(tagLineRange) === io.cpu.fetch.address(tagLineRange)
    val loaderHitReady = lineLoader.loadedWordsReadable(io.cpu.fetch.address(wordRange))


    io.cpu.fetch.haltIt := io.cpu.fetch.isValid && !(waysHitValid || (loaderHitValid && loaderHitReady))
    io.cpu.fetch.data := waysHitWord //TODO
    if(catchAccessFault) io.cpu.fetch.error := (waysHitValid && waysHitError) ||  (loaderHitValid && loaderHitReady && lineLoader.loadingWithErrorReg)
    lineLoader.requestIn.valid := io.cpu.fetch.isValid && !io.cpu.fetch.isStuckByOthers && !waysHitValid
    lineLoader.requestIn.addr := io.cpu.fetch.address
  } else new Area{

    val waysHitValid = False
    val waysHitError = Bool.assignDontCare()
    val waysHitWord = Bits(wordWidth bit)

    val waysRead = for(way <- ways) yield new Area{
      val tag = if(asyncTagMemory)
          way.tags.readAsync(io.cpu.fetch.address(lineRange))
        else
          way.tags.readSync(io.cpu.prefetch.address(lineRange),enable = !io.cpu.fetch.isStuck)

      val data = way.datas.readSync(io.cpu.prefetch.address(lineRange.high downto wordRange.low),enable = !io.cpu.fetch.isStuck)
      waysHitWord := data //Not applicable to multi way
      when(tag.valid && tag.address === io.cpu.fetch.address(tagRange)) {
        waysHitValid := True
        if(catchAccessFault) waysHitError := tag.error
      }

      when(lineLoader.request.valid && lineLoader.request.addr(lineRange) === io.cpu.fetch.address(lineRange)){
        waysHitValid := False //Not applicable to multi way
      }
    }



    val loadedWord = new Area{
      val valid   = RegNext(lineLoader.waysWritePort.valid)
      val address = RegNext(lineLoader.request.addr(tagLineRange) @@ lineLoader.wordIndex @@ U"00")
      val data    = RegNext(lineLoader.waysWritePort.data)
      val wasLoaded = RegNext(lineLoader.loadedWords)
    }


    val fetchInstructionValid = Bool
    val fetchInstructionValue = Bits(32 bits)
    val fetchInstructionValidReg = Reg(Bool)
    val fetchInstructionValueReg = Reg(Bits(32 bits))

    when(fetchInstructionValidReg){
      fetchInstructionValid := True
      fetchInstructionValue := fetchInstructionValueReg
    }.elsewhen(loadedWord.valid && (loadedWord.address >> 2) === (io.cpu.fetch.address >> 2)){
      fetchInstructionValid := True
      fetchInstructionValue := loadedWord.data
    } otherwise{
      fetchInstructionValid := waysHitValid || (loadedWord.address(tagLineRange) === io.cpu.fetch.address(tagLineRange) && loadedWord.wasLoaded(io.cpu.fetch.address(wordRange)))
      fetchInstructionValue := waysHitWord //Not multi way (wasloaded)
    }


    when(io.cpu.fetch.isStuck){
      fetchInstructionValidReg := fetchInstructionValid
      fetchInstructionValueReg := fetchInstructionValue
    } otherwise {
      fetchInstructionValidReg := False
    }


    val decodeInstructionValid = Reg(Bool)
    val decodeInstructionReg = Reg(Bits(32 bits))

    when(!io.cpu.decode.isStuck){
      decodeInstructionValid := fetchInstructionValid
      decodeInstructionReg   := fetchInstructionValue
    }.elsewhen(loadedWord.valid && (loadedWord.address >> 2) === (io.cpu.decode.address >> 2)){
      decodeInstructionValid := True
      decodeInstructionReg   := loadedWord.data
    }

    io.cpu.decode.haltIt       := io.cpu.decode.isValid && !decodeInstructionValid
    io.cpu.decode.data  := decodeInstructionReg

    lineLoader.requestIn.valid := io.cpu.decode.isValid && !decodeInstructionValid
    lineLoader.requestIn.addr  := io.cpu.decode.address

  }

  io.flush.cmd.ready := !(lineLoader.request.valid || io.cpu.fetch.isValid)
}






//object InstructionCacheMain{
//
//  def main(args: Array[String]) {
//    implicit val p = InstructionCacheConfig(
//      cacheSize =4096,
//      bytePerLine =32,
//      wayCount = 1,
//      wrappedMemAccess = true,
//      addressWidth = 32,
//      cpuDataWidth = 32,
//      memDataWidth = 32,
//      catchAccessFault = true)
//    //    val io = new Bundle{
//    //      val cpu = slave(InstructionCacheCpuBus())
//    //      val mem = master(InstructionCacheMemBus())
//    //    }
//
//    SpinalVhdl(new InstructionCache(p))
//  }
//}
//
