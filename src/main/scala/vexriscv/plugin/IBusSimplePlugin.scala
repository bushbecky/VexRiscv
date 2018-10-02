package vexriscv.plugin

import vexriscv._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.avalon.{AvalonMM, AvalonMMConfig}
import spinal.lib.bus.wishbone.{Wishbone, WishboneConfig}



case class IBusSimpleCmd() extends Bundle{
  val pc = UInt(32 bits)
}

case class IBusSimpleRsp() extends Bundle with IMasterSlave{
  val error = Bool
  val inst  = Bits(32 bits)

  override def asMaster(): Unit = {
    out(error,inst)
  }
}


object IBusSimpleBus{
  def getAxi4Config() = Axi4Config(
    addressWidth = 32,
    dataWidth = 32,
    useId = false,
    useRegion = false,
    useBurst = false,
    useLock = false,
    useQos = false,
    useLen = false,
    useResp = true,
    useSize = false
  )

  def getAvalonConfig() = AvalonMMConfig.pipelined(
    addressWidth = 32,
    dataWidth = 32
  ).getReadOnlyConfig.copy(
    useResponse = true,
    maximumPendingReadTransactions = 8
  )

  def getWishboneConfig() = WishboneConfig(
    addressWidth = 30,
    dataWidth = 32,
    selWidth = 4,
    useSTALL = false,
    useLOCK = false,
    useERR = true,
    useRTY = false,
    tgaWidth = 0,
    tgcWidth = 0,
    tgdWidth = 0,
    useBTE = true,
    useCTI = true
  )
}


case class IBusSimpleBus(interfaceKeepData : Boolean) extends Bundle with IMasterSlave {
  var cmd = Stream(IBusSimpleCmd())
  var rsp = Flow(IBusSimpleRsp())

  override def asMaster(): Unit = {
    master(cmd)
    slave(rsp)
  }


  def toAxi4ReadOnly(): Axi4ReadOnly = {
    assert(!interfaceKeepData)
    val axi = Axi4ReadOnly(IBusSimpleBus.getAxi4Config())

    axi.ar.valid := cmd.valid
    axi.ar.addr  := cmd.pc(axi.readCmd.addr.getWidth -1 downto 2) @@ U"00"
    axi.ar.prot  := "110"
    axi.ar.cache := "1111"
    cmd.ready := axi.ar.ready


    rsp.valid := axi.r.valid
    rsp.inst := axi.r.data
    rsp.error := !axi.r.isOKAY()
    axi.r.ready := True


    //TODO remove
    val axi2 = Axi4ReadOnly(IBusSimpleBus.getAxi4Config())
    axi.ar >-> axi2.ar
    axi.r << axi2.r
//    axi2 << axi
    axi2
  }

  def toAvalon(): AvalonMM = {
    assert(!interfaceKeepData)
    val avalonConfig = IBusSimpleBus.getAvalonConfig()
    val mm = AvalonMM(avalonConfig)

    mm.read := cmd.valid
    mm.address := (cmd.pc >> 2) @@ U"00"
    cmd.ready := mm.waitRequestn

    rsp.valid := mm.readDataValid
    rsp.inst := mm.readData
    rsp.error := mm.response =/= AvalonMM.Response.OKAY

    mm
  }

  def toWishbone(): Wishbone = {
    val wishboneConfig = IBusSimpleBus.getWishboneConfig()
    val bus = Wishbone(wishboneConfig)
    val cmdPipe = cmd.stage()

    bus.ADR := (cmdPipe.pc >>  2)
    bus.CTI := B"000"
    bus.BTE := "00"
    bus.SEL := "1111"
    bus.WE  := False
    bus.DAT_MOSI.assignDontCare()
    bus.CYC := cmdPipe.valid
    bus.STB := cmdPipe.valid


    cmdPipe.ready := cmdPipe.valid && bus.ACK
    rsp.valid := bus.CYC && bus.ACK
    rsp.inst := bus.DAT_MISO
    rsp.error := False //TODO
    bus
  }

}






class IBusSimplePlugin(resetVector : BigInt,
                       catchAccessFault : Boolean = false,
                       relaxedPcCalculation : Boolean = false,
                       prediction : BranchPrediction = NONE,
                       historyRamSizeLog2 : Int = 10,
                       keepPcPlus4 : Boolean = false,
                       compressedGen : Boolean = false,
                       busLatencyMin : Int = 1,
                       pendingMax : Int = 7,
                       injectorStage : Boolean = true
                      ) extends IBusFetcherImpl(
    catchAccessFault = catchAccessFault,
    resetVector = resetVector,
    keepPcPlus4 = keepPcPlus4,
    decodePcGen = compressedGen,
    compressedGen = compressedGen,
    cmdToRspStageCount = busLatencyMin + (if(relaxedPcCalculation) 1 else 0),
    injectorReadyCutGen = false,
    prediction = prediction,
    historyRamSizeLog2 = historyRamSizeLog2,
    injectorStage = injectorStage){

  var iBus : IBusSimpleBus = null
  var decodeExceptionPort : Flow[ExceptionCause] = null

  override def setup(pipeline: VexRiscv): Unit = {
    super.setup(pipeline)
    iBus = master(IBusSimpleBus(false)).setName("iBus")

    if(catchAccessFault) {
      val exceptionService = pipeline.service(classOf[ExceptionService])
      decodeExceptionPort = exceptionService.newExceptionPort(pipeline.decode,1)
    }
  }

  override def build(pipeline: VexRiscv): Unit = {
    import pipeline._
    import pipeline.config._

    pipeline plug new FetchArea(pipeline) {
      //Avoid sending to many iBus cmd
      val pendingCmd = Reg(UInt(log2Up(pendingMax + 1) bits)) init (0)
      val pendingCmdNext = pendingCmd + iBus.cmd.fire.asUInt - iBus.rsp.fire.asUInt
      pendingCmd := pendingCmdNext

      val cmd = /*if(relaxedPcCalculation) new Area {
        //This implementation keep the iBus.cmd on the bus until it's executed, even if the pipeline is flushed
        def stage = iBusRsp.stages(1)
        stage.halt setWhen(iBus.cmd.isStall)
        val cmdKeep = RegInit(False) setWhen(iBus.cmd.valid) clearWhen(iBus.cmd.ready)
        val cmdFired = RegInit(False) setWhen(iBus.cmd.fire) clearWhen(stage.input.ready)
        iBus.cmd.valid := (stage.input.valid || cmdKeep) && pendingCmd =/= pendingMax && !cmdFired
        iBus.cmd.pc := stage.input.payload(31 downto 2) @@ "00"
      } else */new Area {
        //This implementation keep the iBus.cmd on the bus until it's executed or the the pipeline is flushed (not "safe")
        def stage = iBusRsp.stages(if(relaxedPcCalculation) 1 else 0)
        stage.halt setWhen(stage.input.valid && (!iBus.cmd.valid || !iBus.cmd.ready))
        iBus.cmd.valid := stage.input.valid && stage.output.ready && pendingCmd =/= pendingMax
        iBus.cmd.pc := stage.input.payload(31 downto 2) @@ "00"
      }



      val rsp = new Area {
        import iBusRsp._
        //Manage flush for iBus transactions in flight
        val discardCounter = Reg(UInt(log2Up(pendingMax + 1) bits)) init (0)
        discardCounter := discardCounter - (iBus.rsp.fire && discardCounter =/= 0).asUInt
        when(flush) {
//          discardCounter := (if(relaxedPcCalculation) pendingCmd + iBus.cmd.valid.asUInt - iBus.rsp.fire.asUInt else pendingCmd - iBus.rsp.fire.asUInt)
          discardCounter := (if(relaxedPcCalculation) pendingCmdNext else pendingCmd - iBus.rsp.fire.asUInt)
        }

        val rspBuffer = StreamFifoLowLatency(IBusSimpleRsp(), busLatencyMin)
        rspBuffer.io.push << iBus.rsp.throwWhen(discardCounter =/= 0).toStream
        rspBuffer.io.flush := flush

        val fetchRsp = FetchRsp()
        fetchRsp.pc := stages.last.output.payload
        fetchRsp.rsp := rspBuffer.io.pop.payload
        fetchRsp.rsp.error.clearWhen(!rspBuffer.io.pop.valid) //Avoid interference with instruction injection from the debug plugin


        var issueDetected = False
        val join = Stream(FetchRsp())
        join.valid := stages.last.output.valid && rspBuffer.io.pop.valid
        join.payload := fetchRsp
        stages.last.output.ready := stages.last.output.valid ? join.fire | join.ready
        rspBuffer.io.pop.ready := join.fire
        output << join.haltWhen(issueDetected)

        if(catchAccessFault){
          decodeExceptionPort.valid := False
          decodeExceptionPort.code  := 1
          decodeExceptionPort.badAddr := join.pc
          when(join.valid && join.rsp.error && !issueDetected){
            issueDetected \= True
            decodeExceptionPort.valid  := iBusRsp.readyForError
          }
        }
      }
    }
  }
}