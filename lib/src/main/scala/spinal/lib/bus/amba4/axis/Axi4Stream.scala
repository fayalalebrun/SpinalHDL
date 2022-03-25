package spinal.lib.bus.amba4.axis

import spinal.core._
import spinal.lib._

/**
 * Axi4-Stream configuration
 * @param dataWidth Width of the bus in BYTES
 * @param idWidth Width of the ID field in bits
 * @param destWidth Width of the Destination field in bits
 * @param userWidth Width of the User field in bits
 * @param useStrb Use byte strobe bits
 * @param useKeep Use byte keep bits
 * @param useLast Use last bit
 * @param useId Use ID field, must specify idWidth
 * @param useDest Use Destination field, must specify destWidth
 * @param useUser Use User field, must specify userWidth
 */
case class Axi4StreamConfig(dataWidth: Int,
                            idWidth:   Int = -1,
                            destWidth: Int = -1,
                            userWidth: Int = -1,
                            useStrb:   Boolean = false,
                            useKeep:   Boolean = false,
                            useLast:   Boolean = false,
                            useId:     Boolean = false,
                            useDest:   Boolean = false,
                            useUser:   Boolean = false)

object Axi4Stream {

  case class Axi4StreamBundle(val config: Axi4StreamConfig) extends Bundle {
    val data = Bits(8*config.dataWidth bit)
    val id =   (config.useId)   generate UInt(config.idWidth bit)
    val strb = (config.useStrb) generate Bits(config.dataWidth bit)
    val tkeep = (config.useKeep) generate Bits(config.dataWidth bit)
    val last = (config.useLast) generate Bool()
    val dest = (config.useDest) generate UInt(config.destWidth bit)
    val user = (config.useUser) generate Bits(config.userWidth*config.dataWidth bit)

    def isLast = if (this.last != null) this.last else False

    override def clone: Axi4StreamBundle = Axi4StreamBundle(config)
  }

  type Axi4Stream = Stream[Axi4StreamBundle]

  def apply(config: Axi4StreamConfig): Stream[Axi4StreamBundle] = Stream(Axi4StreamBundle(config))

  implicit class Axi4StreamRich(stream: Stream[Axi4StreamBundle]) {
     def connectFrom(that: Stream[Axi4StreamBundle]): Stream[Axi4StreamBundle] = Axi4StreamPriv.connectFrom(that, stream)

    /**
      * Converts the Axi4Stream into a Stream of a specified type.
      * Does not support TSTRB or TKEEP. Will only convert continuous and aligned streams.
      * If TLAST was present it will be dropped. Consider toStreamFragment
      * @param newType Resulting stream payload type
      * @param endianness Extract bits from LSB or MSB, drops additional bits
      * @tparam T Stream payload type
      * @return Stream of type T
      */
    def toStream[T <: Data](newType: HardType[T], endianness: Endianness = LITTLE): Stream[T] = {
      val that = Stream(newType())
      assert(!stream.config.useStrb, s"Can't convert Axi4Stream $this to Stream of ${that.payloadType} because the Axi4Stream supports TSTRB.")
      assert(stream.config.dataWidth == Math.ceil(that.payload.getBitsWidth/8.0).toInt, s"Can't convert Axi4Stream $this (${stream.config.dataWidth} bytes) to Stream of ${that.payloadType} (${Math.ceil(that.payloadType.getBitsWidth/8.0).toInt} bytes) because the payload sizes do not match.")
      if (stream.config.useLast)
        SpinalWarning(s"Axi4Stream $this converted to Stream of ${that.payloadType} discards TLAST. Consider using toFragmentStream instead.")

      that.arbitrationFrom(stream)
      if (endianness == LITTLE)
        that.payload.assignFromBits(stream.data.takeLow(that.payload.getBitsWidth))
      else
        that.payload.assignFromBits(stream.data.takeHigh(that.payload.getBitsWidth))

      that
    }

    /**
      * Converts the Axi4Stream into a Stream Fragment of of a specified type.
      * Does not support TSTRB or TKEEP. Will only convert continuous and aligned streams.
      * @param newType Resulting stream payload type
      * @param endianness Extract bits from LSB or MSB, drops additional bits
      * @tparam T Stream payload type
      * @return Stream of type T
      */
    def toStreamFragment[T <: Data](newType: HardType[T], endianness: Endianness = LITTLE): Stream[Fragment[T]] = {
      val that = Stream(Fragment(newType()))
      assert(!stream.config.useStrb, s"Can't convert Axi4Stream $this to Stream of ${that.payloadType} because the Axi4Stream supports TSTRB.")
      assert(stream.config.dataWidth == Math.ceil(that.payload.getBitsWidth/8.0).toInt, s"Can't convert Axi4Stream $this (${stream.config.dataWidth} bytes) to Stream of ${that.payloadType} (${Math.ceil(that.payloadType.getBitsWidth/8.0).toInt} bytes) because the payload sizes do not match.")
      assert(stream.config.useLast, "Can't convert Axi4Stream $this to Fragment Stream of ${that.payloadType} because the Axi4Stream doesn't support TLAST")

      that.arbitrationFrom(stream)
      if (endianness == LITTLE)
        that.payload.assignFromBits(stream.data.takeLow(that.payload.getBitsWidth))
      else
        that.payload.assignFromBits(stream.data.takeHigh(that.payload.getBitsWidth))
      that.last := stream.last

      that
    }

    /**
      * Results a Flow of TID. To be used with toStream functions.
      * @return Flow of Axi4Stream TID field
      */
    def getIdFlow(): Flow[UInt] = {
      val idFlow = Flow(if (stream.id != null) stream.id else UInt(0 bit))
      idFlow.valid := stream.valid
      idFlow.payload := stream.id
      idFlow
    }

    /**
      * Results a Flow of TDEST. To be used with toStream functions.
      * @return Flow of Axi4Stream TDEST field
      */
    def getDestFlow(): Flow[UInt] = {
      val destFlow = Flow(if (stream.dest != null) stream.dest else UInt(0 bit))
      destFlow.valid := stream.valid
      destFlow.payload := stream.dest
      destFlow
    }

    /**
      * Results a Flow of TUSER. To be used with toStream functions.
      * @return Flow of Axi4Stream TUSER field
      */
    def getUserFlow(): Flow[Bits] = {
      val userFlow = Flow(if (stream.user != null) stream.user else Bits(0 bit))
      userFlow.valid := stream.valid
      userFlow.payload := stream.user
      userFlow
    }
  }

  object Axi4StreamPriv {
    def driveWeak[T <: Data](source : Bundle,sink : Bundle, by : T,to : T,defaultValue : () => T,allowResize : Boolean,allowDrop : Boolean) : Unit = {
      (to != null,by != null) match {
        case (false,false) =>
        case (true,false) => if(defaultValue != null) to := defaultValue() else LocatedPendingError(s"$source can't drive $to because the corresponding source signal does not exist")
        case (false,true) => if(!allowDrop) LocatedPendingError(s"$by can't drive $sink because the corresponding sink signal does not exist")
        case (true,true) => to := (if(allowResize) by.resized else by)
      }
    }

    def resizeOrTrim(source: Bits, sink: Bits): Unit = sink(Math.min(source.getBitsWidth, sink.getBitsWidth) downto 0) := source.resize(Math.max(source.getBitsWidth, sink.getBitsWidth))

    def connectFrom[T <: Axi4StreamBundle](source: Stream[T], sink: Stream[T]): Stream[T] = {
      sink.arbitrationFrom(source)
      assert(source.config.dataWidth <= sink.config.dataWidth, s"Axi4Stream $source directly drives stream $sink with smaller data width! (${source.config.dataWidth} > ${sink.config.dataWidth})")
      assert(source.config.idWidth <= sink.config.idWidth, s"Axi4Stream $source directly drives stream $sink with smaller ID width! (${source.config.idWidth} > ${sink.config.idWidth})")
      assert(source.config.destWidth <= sink.config.destWidth, s"Axi4Stream $source directly drives stream $sink with smaller destination width! (${source.config.destWidth} > ${sink.config.destWidth})")
      assert(source.config.userWidth <= sink.config.userWidth, s"Axi4Stream $source directly drives stream $sink with smaller user width! (${source.config.userWidth} > ${sink.config.userWidth})")

      sink.data := source.data.resized
      driveWeak(source,sink,source.id,sink.id, () => U(sink.id.bitsRange -> false), allowResize = true, allowDrop = false)
      driveWeak(source,sink,source.strb,sink.strb, () => B(sink.strb.bitsRange -> true), allowResize = true, allowDrop = false)
      driveWeak(source,sink,source.tkeep,sink.tkeep, () => B(sink.tkeep.bitsRange -> true), allowResize = true, allowDrop = false)
      driveWeak(source,sink,source.last,sink.last, () => False, allowResize = false,allowDrop = false)
      driveWeak(source,sink,source.dest,sink.dest, () => B(sink.dest.bitsRange -> true), allowResize = true, allowDrop = false)

      (sink.user != null, source.user != null) match {
        case (false, false) =>
        case (true, false) => B(sink.user.bitsRange -> false)
        case (false, true) => LocatedPendingError(s"${source.user} can't drive $sink because the corresponding sink signal does not exist")
        case (true, true) => {
          val sourceSlices = source.user.subdivideIn(source.config.dataWidth slices)
          val sinkSlices = sink.user.subdivideIn(sink.config.dataWidth slices)
          val sourceSlicesPad = sourceSlices.padTo(sinkSlices.length, null)
          sourceSlicesPad.zip(sinkSlices).foreach(pair => {
            val (sourceSlice, sinkSlice) = pair
            if (sourceSlice != null)
              resizeOrTrim(sourceSlice, sinkSlice)
            else
              sinkSlice := B(sinkSlice.bitsRange -> false)
          })
        }
      }

      sink
    }
  }

  implicit class RichStream[T <: Data](val source: Stream[T]) {
    /**
      * Maps a source as a AXI4-Stream. Supports optional MSB bit alignment
      * @param endianness Align the data to LSB (left expansion) or MSB (shifting left)
      * @return Stream with Axi4Stream payload
      */
    def toAxi4Stream(endianness: Endianness = LITTLE): Axi4Stream = {
      val payloadBytes = (source.payload.getBitsWidth/8.0).ceil.intValue()
      val axisStream = Axi4Stream(Axi4StreamConfig(dataWidth = payloadBytes))
      endianness match {
        case LITTLE => axisStream.data := source.payload.asBits.resize(payloadBytes*8)
        case BIG => axisStream.data := source.payload.asBits.resizeLeft(payloadBytes*8)
      }
      axisStream.arbitrationFrom(source)
      axisStream
    }
  }

  implicit class RichStreamFragment[T <: Data](val source: Stream[Fragment[T]]) {
    /**
      * Maps a source as a AXI4-Stream with TLAST. Supports optional MSB bit alignment
      * @param endianness Align the data to LSB (left expansion) or MSB (shifting left)
      * @return Stream with Axi4Stream payload
      */
    def toAxi4Stream(endianness: Endianness = LITTLE): Axi4Stream = {
      source.map(_.fragment).toAxi4Stream(endianness).map(axisPayload => {
        val axisWithLast = Axi4Stream(axisPayload.config.copy(useLast = true))
        axisWithLast.assignSomeByName(axisPayload)
        axisWithLast.last := source.last
        axisWithLast
      })
    }
  }
}