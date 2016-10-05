package zynq

import Chisel._
import junctions._
import junctions.NastiConstants._
import cde.{Parameters, Field}
import util._
import testchipip._
import rocket.XLen

case object BuildSerialDriver extends Field[Parameters => SerialDriver]

abstract class SerialDriver(w: Int) extends Module {
  val io = new Bundle {
    val serial = new SerialIO(w).flip
    val exit = Bool(OUTPUT)
  }
}

class SimSerial(w: Int) extends BlackBox {
  val io = new Bundle {
    val clock = Clock(INPUT)
    val reset = Bool(INPUT)
    val serial = new SerialIO(w).flip
    val exit = Bool(OUTPUT)
  }
}

class SimSerialWrapper(w: Int) extends SerialDriver(w) {
  val bbox = Module(new SimSerial(w))
  bbox.io.clock := clock
  bbox.io.reset := reset
  bbox.io.serial <> io.serial
  io.exit := bbox.io.exit
}

class IntegrationTestDriver(implicit p: Parameters) extends NastiModule()(p) {
  val io = new Bundle {
    val nasti = new NastiIO
    val exit = Bool(OUTPUT)
  }

  require(p(XLen) == 64)
  require(p(SerialInterfaceWidth) == 32)
  require(nastiXDataBits == 32)

  val startAddr = 0x80000000L
  val testLen = 0x40

  val (cmd_read :: cmd_write :: Nil) = Enum(Bits(), 2)

  val (s_idle :: s_write_addr :: s_write_data :: s_write_resp ::
       s_read_addr :: s_read_data :: s_done :: Nil) = Enum(Bits(), 7)
  val state = Reg(init = s_idle)

  val testData = Vec(Seq.tabulate(testLen)(i => UInt(i * 3)))
  val idx = Reg(UInt(width = 32))

  val writeData = MuxCase(UInt(0), Seq(
    (idx === UInt(0)) -> cmd_write,
    (idx === UInt(1)) -> UInt(startAddr),
    (idx === UInt(3)) -> UInt(testLen - 1),
    (idx >= UInt(5) && idx < UInt(5 + testLen)) -> testData(idx - UInt(5)),
    (idx === UInt(5 + testLen)) -> cmd_read,
    (idx === UInt(6 + testLen)) -> UInt(startAddr),
    (idx === UInt(8 + testLen)) -> UInt(testLen - 1)))

  val lastWriteIdx = 9 + testLen

  when (state === s_idle) { state := s_write_addr }

  when (io.nasti.aw.fire()) {
    idx := UInt(0)
    state := s_write_data
  }

  when (io.nasti.w.fire()) {
    idx := idx + UInt(1)
    when (idx === UInt(lastWriteIdx)) {
      state := s_write_resp
    }
  }

  when (io.nasti.b.fire()) { state := s_read_addr }

  when (io.nasti.ar.fire()) {
    idx := UInt(0)
    state := s_read_data
  }

  when (io.nasti.r.fire()) {
    idx := idx + UInt(1)
    when (io.nasti.r.bits.last) {
      state := s_done
    }
  }

  io.exit := (state === s_done)

  io.nasti.aw.valid := (state === s_write_addr)
  io.nasti.aw.bits := NastiWriteAddressChannel(
    id = UInt(0),
    addr = UInt(0x43C00008L),
    size = UInt(2),
    len = UInt(lastWriteIdx),
    burst = BURST_FIXED)

  io.nasti.w.valid := (state === s_write_data)
  io.nasti.w.bits := NastiWriteDataChannel(
    data = writeData,
    last = idx === UInt(lastWriteIdx))

  io.nasti.ar.valid := (state === s_read_addr)
  io.nasti.ar.bits := NastiReadAddressChannel(
    id = UInt(0),
    addr = UInt(0x43C00000L),
    size = UInt(2),
    len = UInt(testLen - 1),
    burst = BURST_FIXED)

  io.nasti.b.ready := (state === s_write_resp)
  io.nasti.r.ready := (state === s_read_data)

  assert(!io.nasti.b.valid || io.nasti.b.bits.resp === RESP_OKAY,
         "Integration test write error")
  assert(!io.nasti.r.valid || io.nasti.r.bits.data === testData(idx),
         "Integration test data mismatch")
}

class IntegrationTestSerial(implicit p: Parameters) extends SerialDriver(p(SerialInterfaceWidth)) {
  val testParams = AdapterParams(p)
  val fifo = Module(new NastiFIFO()(testParams))
  val driver = Module(new IntegrationTestDriver()(testParams))

  io.exit := driver.io.exit
  fifo.io.nasti <> driver.io.nasti
  fifo.io.serial <> io.serial
}
