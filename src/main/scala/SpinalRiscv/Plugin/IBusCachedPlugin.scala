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
                                   catchMemoryTranslationMiss : Boolean,
                                   asyncTagMemory : Boolean,
                                   twoStageLogic : Boolean){
  def burstSize = bytePerLine*8/memDataWidth
  def catchSomething = catchAccessFault || catchMemoryTranslationMiss

}



class IBusCachedPlugin(config : InstructionCacheConfig, askMemoryTranslation : Boolean = false, memoryTranslatorPortConfig : Any = null) extends Plugin[VexRiscv] {
  import config._
  assert(twoStageLogic || !askMemoryTranslation)

  var iBus  : InstructionCacheMemBus = null
  var mmuBus : MemoryTranslatorBus = null
  var decodeExceptionPort : Flow[ExceptionCause] = null


  object IBUS_ACCESS_ERROR extends Stageable(Bool)
  override def setup(pipeline: VexRiscv): Unit = {
    pipeline.unremovableStages += pipeline.prefetch

    if(catchSomething) {
      val exceptionService = pipeline.service(classOf[ExceptionService])
      decodeExceptionPort = exceptionService.newExceptionPort(pipeline.decode,1)
    }

    if(askMemoryTranslation)
      mmuBus = pipeline.service(classOf[MemoryTranslator]).newTranslationPort(pipeline.fetch, memoryTranslatorPortConfig)
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
    if(!twoStageLogic) {
      fetch.arbitration.haltIt setWhen (cache.io.cpu.fetch.haltIt)
      fetch.insert(INSTRUCTION) := cache.io.cpu.fetch.data
      decode.insert(INSTRUCTION_ANTICIPATED) := Mux(decode.arbitration.isStuck,decode.input(INSTRUCTION),fetch.output(INSTRUCTION))
      decode.insert(INSTRUCTION_READY) := True
    }else {
      if (mmuBus != null) {
        cache.io.cpu.fetch.mmuBus <> mmuBus
      } else {
        cache.io.cpu.fetch.mmuBus.rsp.physicalAddress := cache.io.cpu.fetch.mmuBus.cmd.virtualAddress
        cache.io.cpu.fetch.mmuBus.rsp.allowExecute := True
        cache.io.cpu.fetch.mmuBus.rsp.allowRead := True
        cache.io.cpu.fetch.mmuBus.rsp.allowWrite := True
      }
    }

    cache.io.flush.cmd.valid := False

    if(twoStageLogic){
      cache.io.cpu.decode.isValid := decode.arbitration.isValid
      decode.arbitration.haltIt.setWhen(cache.io.cpu.decode.haltIt)
      cache.io.cpu.decode.isStuck := decode.arbitration.isStuck
      cache.io.cpu.decode.address := decode.input(PC)
      decode.insert(INSTRUCTION)  := cache.io.cpu.decode.data
      decode.insert(INSTRUCTION_ANTICIPATED) := cache.io.cpu.decode.dataAnticipated
      decode.insert(INSTRUCTION_READY) := !cache.io.cpu.decode.haltIt
    }


    if(catchSomething){
      if(catchAccessFault) {
        if (!twoStageLogic) fetch.insert(IBUS_ACCESS_ERROR) := cache.io.cpu.fetch.error
        if (twoStageLogic) decode.insert(IBUS_ACCESS_ERROR) := cache.io.cpu.decode.error
      }

      val accessFault = if(catchAccessFault) decode.input(IBUS_ACCESS_ERROR) else False
      val mmuMiss = if(catchMemoryTranslationMiss) cache.io.cpu.decode.mmuMiss else False

      decodeExceptionPort.valid   := decode.arbitration.isValid && (accessFault || mmuMiss)
      decodeExceptionPort.code    := mmuMiss ? U(14) | 1
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
  val mmuBus  = if(p.twoStageLogic) MemoryTranslatorBus() else null

  override def asMaster(): Unit = {
    out(isValid, isStuck, address)
    outWithNull(isStuckByOthers)
    inWithNull(error,data,haltIt)
    slaveWithNull(mmuBus)
  }
}

case class InstructionCacheCpuDecode(p : InstructionCacheConfig) extends Bundle with IMasterSlave {
  require(p.twoStageLogic)
  val isValid = Bool
  val haltIt  = Bool
  val isStuck = Bool
  val address = UInt(p.addressWidth bit)
  val data    = Bits(32 bit)
  val dataAnticipated = Bits(32 bits)
  val error   = if(p.catchAccessFault) Bool else null
  val mmuMiss   = if(p.catchMemoryTranslationMiss) Bool else null

  override def asMaster(): Unit = {
    out(isValid, isStuck, address)
    in(haltIt, data, dataAnticipated)
    inWithNull(error,mmuMiss)
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
  val lineWordRange = lineRange.high downto wordRange.low

  class LineInfo extends Bundle{
    val valid = Bool
    val loading = Bool
    val error = if(catchAccessFault) Bool else null
    val address = UInt(tagRange.length bit)
  }

  class LineInfoWithHit extends LineInfo{
    val hit = Bool
  }

  def LineInfoWithHit(lineInfo : LineInfo, testTag : UInt) = {
    val ret = new LineInfoWithHit()
    ret.assignSomeByName(lineInfo)
    ret.hit := lineInfo.valid && lineInfo.address === testTag
    ret
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

    val waysDatasWritePort = ways(0).datas.writePort //Not multi ways
    waysDatasWritePort.valid   := io.mem.rsp.valid
    waysDatasWritePort.address := request.addr(lineRange) @@ wordIndex
    waysDatasWritePort.data    := io.mem.rsp.data
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

    val waysTagsWritePort = ways(0).tags.writePort //not multi way
    waysTagsWritePort.valid := io.mem.rsp.valid || !flushCounter.msb
    waysTagsWritePort.address := Mux(flushCounter.msb,request.addr(lineRange),flushCounter(flushCounter.high-1 downto 0))
    waysTagsWritePort.data.valid := flushCounter.msb
    waysTagsWritePort.data.address := request.addr(tagRange)
    waysTagsWritePort.data.loading := !memRspLast
    if(catchAccessFault) waysTagsWritePort.data.error := loadingWithError


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
        way.tags.readAsync(io.cpu.fetch.address(lineRange),writeFirst)
      else
        way.tags.readSync(readAddress(lineRange),readUnderWrite = readFirst)

      val data = way.datas.readSync(readAddress(lineRange.high downto wordRange.low))
      waysHitWord := data //Not applicable to multi way
      when(tag.valid && tag.address === io.cpu.fetch.address(tagRange)) {
        waysHitValid := True
        if(catchAccessFault) waysHitError := tag.error
      }
    }


    val hit = waysHitValid && !(waysRead(0).tag.loading && !(if(asyncTagMemory) lineLoader.loadedWords else RegNext(lineLoader.loadedWords))(io.cpu.fetch.address(wordRange)))
    io.cpu.fetch.haltIt := io.cpu.fetch.isValid && !hit
    io.cpu.fetch.data := waysHitWord
    if(catchAccessFault) io.cpu.fetch.error := waysRead(0).tag.error
    lineLoader.requestIn.valid := io.cpu.fetch.isValid && !hit //TODO avoid duplicated request
    lineLoader.requestIn.addr  := io.cpu.fetch.address
  } else new Area{
    //Long readValidPath
//    def writeFirstMemWrap[T <: Data](readValid : Bool, readAddress : UInt, lastAddress : UInt, readData : T,writeValid : Bool, writeAddress : UInt, writeData : T) : T = {
//      val hit = writeValid && (readValid ? readAddress  | lastAddress) === writeAddress
//      val overrideValid = RegInit(False) clearWhen(readValid) setWhen(hit)
//      val overrideValue = RegNextWhen(writeData,hit)
//      overrideValid ? overrideValue | readData
//    }

    //shot readValid path
    def writeFirstMemWrap[T <: Data](readValid : Bool, readLastAddress : UInt, readData : T,writeValid : Bool, writeAddress : UInt, writeData : T) : T = {
      val writeSample     = readValid || (writeValid && writeAddress === readLastAddress)
      val writeValidReg   = RegNextWhen(writeValid,writeSample)
      val writeAddressReg = RegNextWhen(writeAddress,writeSample)
      val writeDataReg    = RegNextWhen(writeData,writeSample)
      (writeValidReg && writeAddressReg === readLastAddress) ? writeDataReg | readData
    }

    //Long sample path
//    def writeFirstRegWrap[T <: Data](sample : Bool, sampleAddress : UInt,lastAddress : UInt, readData : T, writeValid : Bool, writeAddress : UInt, writeData : T) : (T,T) = {
//      val hit = writeValid && (sample ? sampleAddress | lastAddress) === writeAddress
//      val bypass = hit ? writeData | readData
//      val reg = RegNextWhen(bypass,sample || hit)
//      (reg,bypass)
//    }

    //Short sample path
    def writeFirstRegWrap[T <: Data](sample : Bool, sampleAddress : UInt,sampleLastAddress : UInt, readData : T, writeValid : Bool, writeAddress : UInt, writeData : T) = {
      val preWrite = (writeValid && sampleAddress === writeAddress)
      val postWrite = (writeValid && sampleLastAddress === writeAddress)
      val bypass = (!sample || preWrite) ? writeData | readData
      val regEn = sample || postWrite
      val reg = RegNextWhen(bypass,regEn)
      (reg,bypass,regEn,preWrite,postWrite)
    }
//    def writeFirstRegWrap[T <: Data](sample : Bool, sampleAddress : UInt,sampleLastAddress : UInt, readData : T, writeValid : Bool, writeAddress : UInt, writeData : T) = {
//      val bypass = (!sample || (writeValid && sampleAddress === writeAddress)) ? writeData | readData
//      val regEn = sample || (writeValid && sampleLastAddress === writeAddress)
//      val reg = RegNextWhen(bypass,regEn)
//      (reg,bypass,regEn,False,False)
//    }
    require(wayCount == 1)
    val memRead = new Area{
      val way = ways(0)
      val tag = if(asyncTagMemory)
          way.tags.readAsync(io.cpu.fetch.address(lineRange),writeFirst)
        else
          writeFirstMemWrap(
            readValid = !io.cpu.fetch.isStuck,
//            readAddress = io.cpu.prefetch.address(lineRange),
            readLastAddress = io.cpu.fetch.address(lineRange),
            readData = way.tags.readSync(io.cpu.prefetch.address(lineRange),enable = !io.cpu.fetch.isStuck),
            writeValid = lineLoader.waysTagsWritePort.valid,
            writeAddress = lineLoader.waysTagsWritePort.address,
            writeData = lineLoader.waysTagsWritePort.data
          )

      val data = writeFirstMemWrap(
        readValid = !io.cpu.fetch.isStuck,
//        readAddress = io.cpu.prefetch.address(lineWordRange),
        readLastAddress = io.cpu.fetch.address(lineWordRange),
        readData = way.datas.readSync(io.cpu.prefetch.address(lineWordRange),enable = !io.cpu.fetch.isStuck),
        writeValid = lineLoader.waysDatasWritePort.valid,
        writeAddress = lineLoader.waysDatasWritePort.address,
        writeData = lineLoader.waysDatasWritePort.data
      )
    }


    val tag = writeFirstRegWrap(
      sample = !io.cpu.decode.isStuck,
      sampleAddress = io.cpu.fetch.address(lineRange),
      sampleLastAddress = io.cpu.decode.address(lineRange),
      readData = LineInfoWithHit(memRead.tag,io.cpu.fetch.address(tagRange)),
      writeValid = lineLoader.waysTagsWritePort.valid,
      writeAddress = lineLoader.waysTagsWritePort.address,
      writeData = LineInfoWithHit(lineLoader.waysTagsWritePort.data,io.cpu.fetch.address(tagRange)) //TODO wrong address src
    )._1

    val (data,dataRegIn,dataRegEn,dataPreWrite,dataPostWrite) = writeFirstRegWrap(
      sample = !io.cpu.decode.isStuck,
      sampleAddress = io.cpu.fetch.address(lineWordRange),
      sampleLastAddress = io.cpu.decode.address(lineWordRange),
      readData = memRead.data,
      writeValid = lineLoader.waysDatasWritePort.valid,
      writeAddress = lineLoader.waysDatasWritePort.address,
      writeData = lineLoader.waysDatasWritePort.data
    )

    io.cpu.fetch.mmuBus.cmd.isValid := io.cpu.fetch.isValid
    io.cpu.fetch.mmuBus.cmd.virtualAddress := io.cpu.fetch.address
    io.cpu.fetch.mmuBus.cmd.bypassTranslation := False
    val mmuRsp = RegNextWhen(io.cpu.fetch.mmuBus.rsp,!io.cpu.decode.isStuck)

    val hit = tag.valid && tag.address === mmuRsp.physicalAddress(tagRange) && !(tag.loading && !lineLoader.loadedWords(mmuRsp.physicalAddress(wordRange)))
//    val hit = tag.hit && !(tag.loading && !lineLoader.loadedWords(mmuRsp.physicalAddress(wordRange)))

    io.cpu.decode.haltIt  := io.cpu.decode.isValid && !hit //TODO PERF not halit it when removed, Should probably be applyed in many other places
    io.cpu.decode.data    := data
//    io.cpu.decode.dataAnticipated := dataRegEn ? dataRegIn | data
    io.cpu.decode.dataAnticipated := io.cpu.decode.isStuck ? Mux(dataPostWrite,lineLoader.waysDatasWritePort.data,data) | Mux(dataPreWrite,lineLoader.waysDatasWritePort.data,memRead.data)
    if(catchAccessFault) io.cpu.decode.error := tag.error
    if(catchMemoryTranslationMiss) io.cpu.decode.mmuMiss := mmuRsp.miss

    lineLoader.requestIn.valid := io.cpu.decode.isValid && !hit && !mmuRsp.miss//TODO avoid duplicated request
    lineLoader.requestIn.addr  := mmuRsp.physicalAddress
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
