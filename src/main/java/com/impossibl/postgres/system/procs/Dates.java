package com.impossibl.postgres.system.procs;

import static com.impossibl.postgres.types.PrimitiveType.Date;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.IOException;

import org.jboss.netty.buffer.ChannelBuffer;

import com.impossibl.postgres.datetime.instants.AmbiguousInstant;
import com.impossibl.postgres.datetime.instants.FutureInfiniteInstant;
import com.impossibl.postgres.datetime.instants.Instant;
import com.impossibl.postgres.datetime.instants.PastInfiniteInstant;
import com.impossibl.postgres.system.Context;
import com.impossibl.postgres.types.PrimitiveType;
import com.impossibl.postgres.types.Type;



public class Dates extends SimpleProcProvider {

	public Dates() {
		super(null, null, new Encoder(), new Decoder(), "date_");
	}

	static class Decoder extends BinaryDecoder {

		public PrimitiveType getInputPrimitiveType() {
			return Date;
		}
		
		public Class<?> getOutputType() {
			return Instant.class;
		}

		public Instant decode(Type type, ChannelBuffer buffer, Context context) throws IOException {

			int length = buffer.readInt();
			if(length == -1) {
				return null;
			}
			else if (length != 4) {
				throw new IOException("invalid length");
			}

			int daysPg = buffer.readInt();

			if(daysPg == Integer.MAX_VALUE) {
				return FutureInfiniteInstant.INSTANCE;
			}
			else if(daysPg == Integer.MIN_VALUE) {
				return PastInfiniteInstant.INSTANCE;
			}
			
			long micros = toJavaMicros(daysPg);
			
			return new AmbiguousInstant(Instant.Type.Date, micros);
		}

	}

	static class Encoder extends BinaryEncoder {

		public Class<?> getInputType() {
			return Instant.class;
		}

		public PrimitiveType getOutputPrimitiveType() {
			return Date;
		}
		
		public void encode(Type type, ChannelBuffer buffer, Object val, Context context) throws IOException {
			if (val == null) {
				
				buffer.writeInt(-1);
			}
			else {
				
				Instant inst = (Instant) val;
				
				int daysPg;
				
				if(inst.getType() == Instant.Type.Infinity) {
					daysPg = inst.getMicrosLocal() < 0 ? Integer.MIN_VALUE : Integer.MAX_VALUE;
				}
				else {
					daysPg = toPgDays(inst);
				}
				
				buffer.writeInt(4);
				buffer.writeInt(daysPg);
			}
			
		}

	}

	private static final long PG_EPOCH_SECS = 946684800L;

	static final long DAY_SECS = DAYS.toSeconds(1);
	
	static final long CUTOFF_1_START_SECS	= -13165977600L;	// October 15, 1582 -> October 4, 1582
	static final long CUTOFF_1_END_SECS		= -12219292800L;
	static final long CUTOFF_2_START_SECS = -15773356800L;	// 1500-03-01 -> 1500-02-28
	static final long CUTOFF_2_END_SECS 	= -14825808000L;
	static final long APPROX_YEAR_SECS1		= -3155823050L;
	static final long APPROX_YEAR_SECS2		=  3155760000L;
	
	private static int toPgDays(Instant a) {
		
		long secs = MICROSECONDS.toSeconds(a.getMicrosLocal());
		
		secs -= PG_EPOCH_SECS;
		
    // Julian/Greagorian calendar cutoff point
		
		if(secs < CUTOFF_1_START_SECS) {
			secs -= DAY_SECS * 10;
			if(secs < CUTOFF_2_START_SECS) {
				int years = (int) ((secs - CUTOFF_2_START_SECS) / APPROX_YEAR_SECS1);
				years++;
				years -= years / 4;
				secs += years * DAY_SECS;
			}
		}
		
		return (int) Math.floor((double)secs / (double)DAY_SECS);		
	}

	private static long toJavaMicros(long days) {

		long secs = DAYS.toSeconds(days);
		
		secs += PG_EPOCH_SECS;

		// Julian/Gregorian calendar cutoff point
		
		if(secs < CUTOFF_1_END_SECS) {
			secs += DAY_SECS * 10;
			if(secs < CUTOFF_2_END_SECS) {
				int extraLeaps = (int) ((secs - CUTOFF_2_END_SECS) / APPROX_YEAR_SECS2);
				extraLeaps--;
				extraLeaps -= extraLeaps / 4;
				secs += extraLeaps * DAY_SECS;
			}
		}
		
		return SECONDS.toMicros(secs);
	}

}
