This repository host an RISC-V implementation written in SpinalHDL. There is some specs :

- RV32IM instruction set
- Interrupts and exception handling with the Machine mode from the riscv-privileged-v1.9.1 specification.
- Pipelined on 5 stages (Fetch, Decode, Execute, Memory, WriteBack)
- 1.17 DMIPS/Mhz with all extension
- Optimized for FPGA
- Optional MUL/DIV/REM extension
- Two implementation of shift instructions, Single cycle / shiftNumber cycle
- Each stage could have bypass or interlock hazard logic
- FreeRTOS port https://github.com/Dolu1990/FreeRTOS-RISCV

The hardware description of this CPU is done by using an very software oriented approach
(without any overhead in the generated hardware). There is a list of software concepts used :

- There is very few fixed things. Nearly everything is plugin based. The PC manager is a plugin, the register file is a plugin, the hazard controller is a plugin ...
- There is an automatic a tool which allow plugins to insert data in the pipeline at a given stage, and allow other plugins to read it in another stages through automatic pipelining.
- There is an service system which provide a very dynamic framework. As instance, a plugin could provide an exception service which could then be used by others plugins to emit exceptions from the pipeline.

## Plugin structure

```scala

//Define an signal name/type which could be used in the pipeline
object ALU_ENABLE extends Stageable(Bool)
object ALU_OP     extends Stageable(Bits(2 bits))  // ADD, SUB, AND, OR
object ALU_SRC1   extends Stageable(UInt(32 bits))
object ALU_SRC2   extends Stageable(UInt(32 bits))
object ALU_RESULT extends Stageable(UInt(32 bits))

class AluPlugin() extends Plugin[VexRiscv]{



  override def setup(pipeline: VexRiscv): Unit = {
    import pipeline.config._
    //Do some setups as for example specifying some instruction decoding by using the Decoding service
    val decoderService = pipeline.service(classOf[DecoderService])

    decoderService.addDefault(ALU_ENABLE,False)
    decodingService.add(List(
        //.....
    ))
  }

  override def build(pipeline: VexRiscv): Unit = {
    import pipeline._

    execute plug new Area {
      import execute._
      //Add some logic in the execute stage
      insert(ALU_RESULT) := input(ALU_OP).mux(
        B"00" -> input(ALU_SRC1) + input(ALU_SRC2),
        B"01" -> input(ALU_SRC1) - input(ALU_SRC2),
        B"10" -> input(ALU_SRC1) & input(ALU_SRC2),
        B"11" -> input(ALU_SRC1) | input(ALU_SRC2),
      )
    }

    writeBack plug new Area {
      import writeBack._
      //Add some logic in the execute stage
      when(input(ALU_ENABLE)){
        input(REGFILE_WRITE_DATA) := input(ALU_RESULT)
      }
    }
  }
}
```