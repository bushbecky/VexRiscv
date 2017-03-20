package SpinalRiscv.Plugin

import SpinalRiscv.{JumpService, Stage, VexRiscv}
import spinal.core._
import spinal.lib._

import scala.collection.mutable.ArrayBuffer

class PcManagerSimplePlugin(resetVector : BigInt,fastPcCalculation : Boolean) extends Plugin[VexRiscv] with JumpService{


  //FetchService interface
  case class JumpInfo(interface :  Flow[UInt], stage: Stage)
  val jumpInfos = ArrayBuffer[JumpInfo]()
  override def createJumpInterface(stage: Stage): Flow[UInt] = {
    val interface = Flow(UInt(32 bits))
    jumpInfos += JumpInfo(interface,stage)
    interface
  }


  override def build(pipeline: VexRiscv): Unit = {
    import pipeline.config._
    import pipeline.prefetch

    prefetch plug new Area {
      import prefetch._
      //Stage always valid
      arbitration.isValid := True

      //PC calculation without Jump
      val pcReg = Reg(UInt(pcWidth bits)) init(resetVector) addAttribute("verilator public")
      val inc = RegInit(False)
      val pc = if(fastPcCalculation){
        val pcPlus4 = pcReg + U(4)
        pcPlus4.addAttribute("keep")
        Mux(inc,pcPlus4,pcReg)
      }else{
        pcReg + Mux[UInt](inc,4,0)
      }

      val samplePcNext = False

      //JumpService hardware implementation
      val jump = if(jumpInfos.length != 0) new Area {
        val sortedByStage = jumpInfos.sortWith((a, b) => pipeline.indexOf(a.stage) > pipeline.indexOf(b.stage))
        val valids = sortedByStage.map(_.interface.valid)
        val pcs = sortedByStage.map(_.interface.payload)

        val pcLoad = Flow(UInt(pcWidth bits))
        pcLoad.valid := jumpInfos.foldLeft(False)(_ || _.interface.valid)
        pcLoad.payload := MuxOH(valids, pcs)

        //application of the selected jump request
        when(pcLoad.valid) {
          inc := False
          samplePcNext := True
          pc := pcLoad.payload
        }
      }

      when(arbitration.isFiring){
        inc := True
        samplePcNext := True
      }

      when(samplePcNext) { pcReg := pc }

      insert(PC) := pc
    }
  }
}