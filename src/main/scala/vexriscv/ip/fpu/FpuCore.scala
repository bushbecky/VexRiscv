package vexriscv.ip.fpu

import spinal.core._
import spinal.lib._
import spinal.lib.eda.bench.{Bench, Rtl, XilinxStdTargets}

import scala.collection.mutable.ArrayBuffer

object FpuDivSqrtIterationState extends SpinalEnum{
  val IDLE, YY, XYY, Y2_XYY, DIV, _15_XYY2, Y_15_XYY2, Y_15_XYY2_RESULT, SQRT = newElement()
}

case class FpuCore( portCount : Int, p : FpuParameter) extends Component{
  val io = new Bundle {
    val port = Vec(slave(FpuPort(p)), portCount)
  }

  val portCountWidth = log2Up(portCount)
  val Source = HardType(UInt(portCountWidth bits))


//  val commitPerportCount = 8
  val rfLockCount = 5
  val lockIdType = HardType(UInt(log2Up(rfLockCount) bits))

//  io.port.rsp.valid := False
//  io.port.rsp.payload.assignDontCare()

  case class RfReadInput() extends Bundle{
    val source = Source()
    val opcode = p.Opcode()
    val rs1, rs2, rs3 = p.rfAddress()
    val rd = p.rfAddress()
  }

  case class RfReadOutput() extends Bundle{
    val source = Source()
    val opcode = p.Opcode()
    val lockId = lockIdType()
    val rs1, rs2, rs3 = p.internalFloating()
    val rd = p.rfAddress()
  }


  case class LoadInput() extends Bundle{
    val source = Source()
    val rs1 = p.internalFloating()
    val rd = p.rfAddress()
    val lockId = lockIdType()
  }

  case class StoreInput() extends Bundle{
    val source = Source()
    val rs2 = p.internalFloating()
  }

  case class MulInput() extends Bundle{
    val source = Source()
    val rs1, rs2, rs3 = p.internalFloating()
    val rd = p.rfAddress()
    val lockId = lockIdType()
    val add = Bool()
    val divSqrt = Bool()
    val msb1, msb2 = Bool() //allow usage of msb bits of mul
    val minus = Bool()
  }

  case class DivSqrtInput() extends Bundle{
    val source = Source()
    val rs1, rs2 = p.internalFloating()
    val rd = p.rfAddress()
    val lockId = lockIdType()
    val div = Bool()
  }

  case class AddInput() extends Bundle{
    val source = Source()
    val rs1, rs2 = p.internalFloating()
    val rd = p.rfAddress()
    val lockId = lockIdType()
  }
  
  case class WriteInput() extends Bundle{
    val source = Source()
    val lockId = lockIdType()
    val rd = p.rfAddress()
    val value = p.internalFloating()
  }


  val rf = new Area{
    val ram = Mem(p.internalFloating, 32*portCount)
    val lock = for(i <- 0 until rfLockCount) yield new Area{
      val valid = RegInit(False)
      val source = Reg(Source())
      val address = Reg(p.rfAddress)
      val id = Reg(UInt(log2Up(rfLockCount) bits))
      val commited = Reg(Bool)
      val write = Reg(Bool)
    }
    val lockFree = !lock.map(_.valid).andR
    val lockFreeId = OHMasking.first(lock.map(!_.valid))
  }

  val commitFork = new Area{
    val load, commit = Vec(Stream(FpuCommit(p)), portCount)
    for(i <- 0 until portCount){
      val fork = new StreamFork(FpuCommit(p), 2)
      fork.io.input << io.port(i).commit
      fork.io.outputs(0) >> load(i)
      fork.io.outputs(1) >> commit(i)
    }
  }

  val commitLogic = for(source <- 0 until portCount) yield new Area{
    val fire = False
    val target, hit = Reg(UInt(log2Up(rfLockCount) bits)) init(0)
    when(fire){
      hit := hit + 1
    }

    commitFork.commit(source).ready := False
    when(commitFork.commit(source).valid) {
      for (lock <- rf.lock) {
        when(lock.valid && lock.source === source && lock.id === hit) {
          fire := True
          lock.commited := True
          lock.write := commitFork.commit(source).write
          commitFork.commit(source).ready := True
        }
      }
    }
  }

//  case class CommitLine() extends Bundle{
//    val valid = Bool()
//    val write = Bool()
//  }
//  val commits = for(i <- 0 until portCount) yield new Area{
//    val lines = Vec(CommitLine(), commitPerportCount)
//    lines.foreach(_.valid init(False))
//
//  }

  val read = new Area{
    val arbiter = StreamArbiterFactory.noLock.lowerFirst.build(FpuCmd(p), portCount)
    arbiter.io.inputs <> Vec(io.port.map(_.cmd))

    val s0 = Stream(RfReadInput())
    s0.arbitrationFrom(arbiter.io.output)
    s0.source := arbiter.io.chosen
    s0.payload.assignSomeByName(arbiter.io.output.payload)

    val useRs1, useRs2, useRs3, useRd = False
    switch(s0.opcode){
      is(p.Opcode.LOAD){
        useRd := True
      }
      is(p.Opcode.STORE){
        useRs2 := True
      }
      is(p.Opcode.ADD){
        useRs1 := True
        useRs2 := True
        useRd  := True
      }
      is(p.Opcode.MUL){
        useRs1 := True
        useRs2 := True
        useRd  := True
      }
      is(p.Opcode.DIV){
        useRs1 := True
        useRs2 := True
        useRd  := True
      }
      is(p.Opcode.SQRT){
        useRs1 := True
        useRd  := True
      }
      is(p.Opcode.FMA){
        useRs1 := True
        useRs2 := True
        useRs3 := True //Can be delayed to have less hazard
        useRd  := True
      }
    }

    val hits = List((useRs1, s0.rs1), (useRs2, s0.rs2), (useRs3, s0.rs3), (useRd, s0.rd)).map{case (use, reg) => use && rf.lock.map(l => l.valid && l.source === s0.source && l.address === reg).orR}
    val hazard = hits.orR
    when(s0.fire && useRd){
      for(i <- 0 until portCount){
        when(s0.source === i){
          commitLogic(i).target := commitLogic(i).target + 1
        }
      }
      for(i <- 0 until rfLockCount){
        when(rf.lockFreeId(i)){
          rf.lock(i).valid := True
          rf.lock(i).source := s0.source
          rf.lock(i).address := s0.rd
          rf.lock(i).id := commitLogic.map(_.target).read(s0.source)
          rf.lock(i).commited := False
        }
      }
    }

    val s1 = s0.haltWhen(hazard || !rf.lockFree).m2sPipe()
    val output = s1.swapPayload(RfReadOutput())
    val s1LockId = RegNextWhen(OHToUInt(rf.lockFreeId), !output.isStall)
    output.source := s1.source
    output.opcode := s1.opcode
    output.lockId := s1LockId
    output.rd := s1.rd
    output.rs1 := rf.ram.readSync(s0.source @@ s0.rs1,enable = !output.isStall)
    output.rs2 := rf.ram.readSync(s0.source @@ s0.rs2,enable = !output.isStall)
    output.rs3 := rf.ram.readSync(s0.source @@ s0.rs3,enable = !output.isStall)
  }

  val decode = new Area{
    val input = read.output.combStage()
    input.ready := False

    val loadHit = input.opcode === p.Opcode.LOAD
    val load = Stream(LoadInput())
    load.valid := input.valid && loadHit
    input.ready setWhen(loadHit && load.ready)
    load.source := read.output.source
    load.rd     := read.output.rd
    load.rs1    := read.output.rs1
    load.lockId := read.output.lockId

    val storeHit = input.opcode === p.Opcode.STORE
    val store = Stream(StoreInput())
    input.ready setWhen(storeHit && store.ready)
    store.valid := input.valid && storeHit
    store.source := read.output.source
    store.rs2    := read.output.rs2

    val divSqrtHit = input.opcode === p.Opcode.DIV ||  input.opcode === p.Opcode.SQRT
    val divSqrt = Stream(DivSqrtInput())
    input.ready setWhen(divSqrtHit && divSqrt.ready)
    divSqrt.valid := input.valid && divSqrtHit
    divSqrt.source := read.output.source
    divSqrt.rs1    := read.output.rs1
    divSqrt.rs2    := read.output.rs2
    divSqrt.rd     := read.output.rd
    divSqrt.lockId := read.output.lockId
    divSqrt.div    := input.opcode === p.Opcode.DIV

    val fmaHit = input.opcode === p.Opcode.FMA
    val mulHit = input.opcode === p.Opcode.MUL || fmaHit
    val mul = Stream(MulInput())
    val divSqrtToMul = Stream(MulInput())

    input.ready setWhen(mulHit && mul.ready && !divSqrtToMul.valid)
    mul.valid := input.valid && mulHit || divSqrtToMul.valid

    divSqrtToMul.ready := mul.ready
    mul.payload := divSqrtToMul.payload
    when(!divSqrtToMul.valid) {
      mul.payload.assignSomeByName(read.output.payload)
      mul.add := fmaHit
      mul.divSqrt := False
      mul.msb1 := True
      mul.msb2 := True
      mul.minus := False //TODO
    }

    val addHit = input.opcode === p.Opcode.ADD
    val add = Stream(AddInput())
    val mulToAdd = Stream(AddInput())

    input.ready setWhen(addHit && add.ready && !mulToAdd.valid)
    add.valid := input.valid && addHit || mulToAdd.valid


    mulToAdd.ready := add.ready
    add.payload := mulToAdd.payload
    when(!mulToAdd.valid) {
      add.payload.assignSomeByName(read.output.payload)
    }
  }

  val load = new Area{
    val input = decode.load.stage()
    val filtred = commitFork.load.map(port => port.takeWhen(port.load))
    def feed = filtred(input.source)
    val hazard = !feed.valid
    val output = input.haltWhen(hazard).swapPayload(WriteInput())
    filtred.foreach(_.ready := False)
    feed.ready := input.valid && output.ready
    output.source := input.source
    output.lockId := input.lockId
    output.rd := input.rd
    output.value.assignFromBits(feed.value)
  }


  val rspLogic = new Area{
    val input = decode.store.stage()

    input.ready := io.port.map(_.rsp.ready).read(input.source)
    for(i <- 0 until portCount){
      def rsp = io.port(i).rsp
      rsp.valid := input.valid && input.source === i
      rsp.value := input.rs2.asBits
    }
  }

  val mul = new Area{
    val input = decode.mul.stage()

    val math = new Area {
      val mulA = U(input.msb1) @@ input.rs1.mantissa
      val mulB = U(input.msb2) @@ input.rs2.mantissa
      val mulC = mulA * mulB
      val exp = input.rs1.exponent +^ input.rs2.exponent - ((1 << p.internalExponentSize - 1) - 1)
    }

    val norm = new Area{
      val needShift = math.mulC.msb
      val exp = math.exp + U(needShift)
      val man = needShift ? math.mulC(p.internalMantissaSize + 1, p.internalMantissaSize bits) | math.mulC(p.internalMantissaSize, p.internalMantissaSize bits)

      val output = FpuFloat(p.internalExponentSize, p.internalMantissaSize)
      output.sign := input.rs1.sign ^ input.rs2.sign
      output.exponent := exp.resized
      output.mantissa := man
    }

    val notMul = new Area{
      val output = Flow(UInt(p.internalMantissaSize + 1 bits))
      output.valid := input.valid && input.divSqrt
      output.payload := math.mulC(p.internalMantissaSize, p.internalMantissaSize+1 bits)
    }

    val output = Stream(WriteInput())
    output.valid  := input.valid && !input.add && !input.divSqrt
    output.source := input.source
    output.lockId := input.lockId
    output.rd     := input.rd
    output.value  := norm.output

    decode.mulToAdd.valid := input.valid && input.add
    decode.mulToAdd.source := input.source
    decode.mulToAdd.rs1.mantissa := norm.output.mantissa
    decode.mulToAdd.rs1.exponent := norm.output.exponent
    decode.mulToAdd.rs1.sign := norm.output.sign ^ input.minus
    decode.mulToAdd.rs2 := input.rs3
    decode.mulToAdd.rd := input.rd
    decode.mulToAdd.lockId := input.lockId

    input.ready := (input.add ? decode.mulToAdd.ready | output.ready) || input.divSqrt
  }

  val divSqrt = new Area {
    val input = decode.divSqrt.stage()

    val aproxWidth = 8
    val aproxDepth = 64
    val divIterationCount = 3
    val sqrtIterationCount = 3

    val mulWidth = p.internalMantissaSize + 1

    import FpuDivSqrtIterationState._
    val state     = RegInit(FpuDivSqrtIterationState.IDLE())
    val iteration = Reg(UInt(log2Up(divIterationCount max sqrtIterationCount) bits))

    decode.divSqrtToMul.valid := False
    decode.divSqrtToMul.source := input.source
    decode.divSqrtToMul.rs1.assignDontCare()
    decode.divSqrtToMul.rs2.assignDontCare()
    decode.divSqrtToMul.rs3.assignDontCare()
    decode.divSqrtToMul.rd := input.rd
    decode.divSqrtToMul.lockId := input.lockId
    decode.divSqrtToMul.add := False
    decode.divSqrtToMul.divSqrt := True
    decode.divSqrtToMul.msb1 := True
    decode.divSqrtToMul.msb2 := True
    decode.divSqrtToMul.minus := False


    val aprox = new Area {
      val rom = Mem(UInt(aproxWidth bits), aproxDepth * 2)
      val divTable, sqrtTable = ArrayBuffer[Double]()
      for(i <- 0 until aproxDepth){
        val value = 1+(i+0.5)/aproxDepth
        divTable += 1/value
      }
      for(i <- 0 until aproxDepth){
        val scale = if(i < aproxDepth/2) 2 else 1
        val value = scale+(scale*(i%(aproxDepth/2)+0.5)/aproxDepth*2)
//        println(s"$i => $value" )
        sqrtTable += 1/Math.sqrt(value)
      }
      val romElaboration = (sqrtTable ++ divTable).map(v => BigInt(((v-0.5)*2*(1 << aproxWidth)).round))

      rom.initBigInt(romElaboration)
      val div = input.rs2.mantissa.takeHigh(log2Up(aproxDepth))
      val sqrt = U(input.rs1.exponent.lsb ## input.rs1.mantissa).takeHigh(log2Up(aproxDepth))
      val address = U(input.div ## (input.div ? div | sqrt))
      val raw = rom.readAsync(address)
      val result = U"01" @@ (raw << (mulWidth-aproxWidth-2))
    }

    val divExp = new Area{
      val value = (1 << p.internalExponentSize) - 3 - input.rs2.exponent
    }
    val sqrtExp = new Area{
      val value = ((1 << p.internalExponentSize-1) + (1 << p.internalExponentSize-2) - 2 -1) - (input.rs1.exponent >> 1) + U(!input.rs1.exponent.lsb)
    }

    def mulArg(rs1 : UInt, rs2 : UInt): Unit ={
      decode.divSqrtToMul.rs1.mantissa := rs1.resized
      decode.divSqrtToMul.rs2.mantissa := rs2.resized
      decode.divSqrtToMul.msb1 := rs1.msb
      decode.divSqrtToMul.msb2 := rs2.msb
    }

    val mulBuffer = mul.notMul.output.toStream.stage
    mulBuffer.ready := False

    val iterationValue = Reg(UInt(mulWidth bits))

    input.ready := False
    switch(state){
      is(IDLE){
        iterationValue := aprox.result
        iteration := 0
        when(input.valid) {
          state := YY
        }
      }
      is(YY){
        decode.divSqrtToMul.valid := True
        mulArg(iterationValue, iterationValue)
        when(decode.divSqrtToMul.ready) {
          state := XYY
        }
      }
      is(XYY){
        decode.divSqrtToMul.valid := mulBuffer.valid
        val sqrtIn = !input.rs1.exponent.lsb ? (U"1" @@ input.rs1.mantissa) | ((U"1" @@ input.rs1.mantissa) |>> 1)
        val divIn = U"1" @@ input.rs2.mantissa
        mulArg(input.div ? divIn| sqrtIn, mulBuffer.payload)
        when(mulBuffer.valid && decode.divSqrtToMul.ready) {
          state := (input.div ? Y2_XYY | _15_XYY2)
          mulBuffer.ready := True
        }
      }
      is(Y2_XYY){
        mulBuffer.ready := True
        when(mulBuffer.valid) {
          iterationValue := ((iterationValue << 1) - mulBuffer.payload).resized
          mulBuffer.ready := True
          iteration := iteration + 1
          when(iteration =/= divIterationCount-1){ //TODO
            state := YY
          } otherwise {
            state := DIV
          }
        }
      }
      is(DIV){
        decode.divSqrtToMul.valid := True
        decode.divSqrtToMul.divSqrt := False
        decode.divSqrtToMul.rs1 := input.rs1
        decode.divSqrtToMul.rs2.sign := input.rs2.sign
        decode.divSqrtToMul.rs2.exponent := divExp.value + iterationValue.msb.asUInt
        decode.divSqrtToMul.rs2.mantissa := (iterationValue << 1).resized
        when(decode.divSqrtToMul.ready) {
          state := IDLE
          input.ready := True
        }
      }
      is(_15_XYY2){
        when(mulBuffer.valid) {
          state := Y_15_XYY2
          mulBuffer.payload.getDrivingReg := (U"11" << mulWidth-2) - (mulBuffer.payload)
        }
      }
      is(Y_15_XYY2){
        decode.divSqrtToMul.valid := True
        mulArg(iterationValue, mulBuffer.payload)
        when(decode.divSqrtToMul.ready) {
          mulBuffer.ready := True
          state := Y_15_XYY2_RESULT
        }
      }
      is(Y_15_XYY2_RESULT){
        iterationValue := mulBuffer.payload
        mulBuffer.ready := True
        when(mulBuffer.valid) {
          iteration := iteration + 1
          when(iteration =/= sqrtIterationCount-1){
            state := YY
          } otherwise {
            state := SQRT
          }
        }
      }
      is(SQRT){
        decode.divSqrtToMul.valid := True
        decode.divSqrtToMul.divSqrt := False
        decode.divSqrtToMul.rs1 := input.rs1
        decode.divSqrtToMul.rs2.sign := False
        decode.divSqrtToMul.rs2.exponent := sqrtExp.value + iterationValue.msb.asUInt
        decode.divSqrtToMul.rs2.mantissa := (iterationValue << 1).resized
        when(decode.divSqrtToMul.ready) {
          state := IDLE
          input.ready := True
        }
      }
    }
  }

  val add = new Area{
    val input = decode.add.stage()

    val shifter = new Area {
      val exp21 = input.rs2.exponent - input.rs1.exponent
      val rs1ExponentBigger = exp21.msb
      val rs1ExponentEqual = input.rs1.exponent === input.rs2.exponent
      val rs1MantissaBigger = input.rs1.mantissa > input.rs2.mantissa
      val absRs1Bigger = rs1ExponentBigger|| rs1ExponentEqual && rs1MantissaBigger
      val shiftBy = rs1ExponentBigger ? (0-exp21) | exp21

      //Note that rs1ExponentBigger can be replaced by absRs1Bigger bellow to avoid xsigned two complement in math block at expense of combinatorial path
      val xySign = absRs1Bigger ? input.rs1.sign | input.rs2.sign
      val xSign = xySign ^ (rs1ExponentBigger ? input.rs1.sign | input.rs2.sign)
      val ySign = xySign ^ (rs1ExponentBigger ? input.rs2.sign | input.rs1.sign)
      val xMantissa = U"1" @@ (rs1ExponentBigger ? input.rs1.mantissa | input.rs2.mantissa)
      val yMantissaUnshifted = U"1" @@ (rs1ExponentBigger ? input.rs2.mantissa | input.rs1.mantissa)
      val yMantissa = yMantissaUnshifted >> shiftBy
      val xyExponent = rs1ExponentBigger ? input.rs1.exponent | input.rs2.exponent
    }

    val math = new Area {
      def xSign = shifter.xSign
      def ySign = shifter.ySign
      def xMantissa = shifter.xMantissa
      def yMantissa = shifter.yMantissa
      def xyExponent = shifter.xyExponent
      def xySign = shifter.xySign

      val xSigned = xMantissa.twoComplement(xSign)
      val ySigned = yMantissa.twoComplement(ySign)
      val xyMantissa = U(xSigned +^ ySigned).trim(1 bits)
    }

    val norm = new Area{
      def xyExponent = math.xyExponent
      def xyMantissa = math.xyMantissa
      def xySign = math.xySign

      val shiftOh = OHMasking.first(xyMantissa.asBools.reverse)
      val shift = OHToUInt(shiftOh)
      val mantissa = (xyMantissa |<< shift) >> 1
      val exponent = xyExponent - shift + 1
    }


    val output = input.swapPayload(WriteInput())
    output.source := input.source
    output.lockId := input.lockId
    output.rd     := input.rd
    output.value.sign := norm.xySign
    output.value.mantissa := norm.mantissa.resized
    output.value.exponent := norm.exponent
  }


  val write = new Area{
    val arbitrated = StreamArbiterFactory.lowerFirst.noLock.on(List(load.output, add.output, mul.output))
    val isCommited = rf.lock.map(_.commited).read(arbitrated.lockId)
    val commited = arbitrated.haltWhen(!isCommited).toFlow

    when(commited.valid){
      for(i <- 0 until rfLockCount) when(commited.lockId === i){
        rf.lock(i).valid := False
      }
    }

    val port = rf.ram.writePort
    port.valid := commited.valid && rf.lock.map(_.write).read(commited.lockId)
    port.address := commited.source @@ commited.rd
    port.data := commited.value
  }
}




object FpuSynthesisBench extends App{
  val payloadType = HardType(Bits(8 bits))
  class Fpu(name : String, portCount : Int, p : FpuParameter) extends Rtl{
    override def getName(): String = "Fpu_" + name
    override def getRtlPath(): String = getName() + ".v"
    SpinalVerilog(new FpuCore(portCount, p){

      setDefinitionName(Fpu.this.getName())
    })
  }



  val rtls = ArrayBuffer[Fpu]()
  rtls += new Fpu(
    "32",
    portCount = 1,
    FpuParameter(
      internalMantissaSize = 23,
      withDouble = false
    )
  )
  rtls += new Fpu(
    "64",
    portCount = 1,
    FpuParameter(
      internalMantissaSize = 52,
      withDouble = true
    )
  )

  val targets = XilinxStdTargets()// ++ AlteraStdTargets()


  Bench(rtls, targets)
}