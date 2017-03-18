package SpinalRiscv.Plugin

import SpinalRiscv.{Stageable, DecoderService, Riscv, VexRiscv}
import spinal.core._
import spinal.lib._


trait RegFileReadKind
object ASYNC extends RegFileReadKind
object SYNC extends RegFileReadKind

class RegFilePlugin(regFileReadyKind : RegFileReadKind) extends Plugin[VexRiscv]{
  import Riscv._

  override def setup(pipeline: VexRiscv): Unit = {
    import pipeline.config._
    val decoderService = pipeline.service(classOf[DecoderService])
    decoderService.addDefault(REG1_USE,False)
    decoderService.addDefault(REG2_USE,False)
  }

  override def build(pipeline: VexRiscv): Unit = {
    import pipeline._
    import pipeline.config._

    val global = pipeline plug new Area{
      val regFile = Mem(Bits(32 bits),32) addAttribute("verilator public")
    }

    decode plug new Area{
      import decode._

      //Disable rd0 write in decoding stage
      when(decode.input(INSTRUCTION)(rdRange) === 0) {
        decode.input(REGFILE_WRITE_VALID) := False
      }

      val rs1 = input(INSTRUCTION)(Riscv.rs1Range).asUInt
      val rs2 = input(INSTRUCTION)(Riscv.rs2Range).asUInt

      //read register file
      val srcInstruction = regFileReadyKind match{
        case `ASYNC` => input(INSTRUCTION)
        case `SYNC` =>  Mux(arbitration.isStuck,input(INSTRUCTION),fetch.output(INSTRUCTION))
      }

      val regFileReadAddress1 = srcInstruction(Riscv.rs1Range).asUInt
      val regFileReadAddress2 = srcInstruction(Riscv.rs2Range).asUInt

      val (rs1Data,rs2Data) = regFileReadyKind match{
        case `ASYNC` => (global.regFile.readAsync(regFileReadAddress1),global.regFile.readAsync(regFileReadAddress2))
        case `SYNC` =>  (global.regFile.readSync(regFileReadAddress1),global.regFile.readSync(regFileReadAddress2))
      }

      insert(REG1) := rs1Data
      insert(REG2) := rs2Data
    }

    writeBack plug new Area {
      import writeBack._

      val regFileWrite = global.regFile.writePort.addAttribute("verilator public")
      regFileWrite.valid := input(REGFILE_WRITE_VALID) && arbitration.isFiring
      regFileWrite.address := input(INSTRUCTION)(rdRange).asUInt
      regFileWrite.data := input(REGFILE_WRITE_DATA)

      //CPU will write constant register zero in the first cycle
      regFileWrite.valid setWhen(RegNext(False) init(True))
      inputInit[Bits](REGFILE_WRITE_DATA, 0)
      inputInit[Bits](INSTRUCTION, 0)
    }
  }
}