package com.impossibl.postgres.system.procs;

import static com.impossibl.postgres.system.Settings.FIELD_MONEY_FRACTIONAL_DIGITS;
import static com.impossibl.postgres.types.PrimitiveType.Money;
import static java.math.RoundingMode.HALF_UP;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;

import org.jboss.netty.buffer.ChannelBuffer;

import com.impossibl.postgres.system.Context;
import com.impossibl.postgres.types.PrimitiveType;
import com.impossibl.postgres.types.Type;


public class Moneys extends SimpleProcProvider {

	public Moneys() {
		super(new TxtEncoder(), new TxtDecoder(), new BinEncoder(), new BinDecoder(), "cash_");
	}

	static class BinDecoder extends BinaryDecoder {

		public PrimitiveType getInputPrimitiveType() {
			return Money;
		}
		
		public Class<?> getOutputType() {
			return BigDecimal.class;
		}

		public BigDecimal decode(Type type, ChannelBuffer buffer, Context context) throws IOException {

			int length = buffer.readInt();
			if (length == -1) {
				return null;
			}
			else if (length != 8) {
				throw new IOException("invalid length");
			}

			long val = buffer.readLong();
			
			int fracDigits = getFractionalDigits(context);
			
			return new BigDecimal(BigInteger.valueOf(val), fracDigits);
		}

	}

	static class BinEncoder extends BinaryEncoder {

		public Class<?> getInputType() {
			return BigDecimal.class;
		}

		public PrimitiveType getOutputPrimitiveType() {
			return Money;
		}
		
		public void encode(Type type, ChannelBuffer buffer, Object val, Context context) throws IOException {

			if (val == null) {
				
				buffer.writeInt(-1);
			}
			else {
				
				int fracDigits = getFractionalDigits(context);
				
				BigDecimal dec = (BigDecimal) val;
				
				dec = dec.setScale(fracDigits, HALF_UP);
				
				buffer.writeInt(8);
				buffer.writeLong(dec.unscaledValue().longValue());
			}
			
		}

	}

	static class TxtDecoder extends TextDecoder {

		public PrimitiveType getInputPrimitiveType() {
			return Money;
		}
		
		public Class<?> getOutputType() {
			return BigDecimal.class;
		}

		public BigDecimal decode(Type type, CharSequence buffer, Context context) throws IOException {
			
			return new BigDecimal(buffer.toString());
		}

	}

	static class TxtEncoder extends TextEncoder {

		public Class<?> getInputType() {
			return BigDecimal.class;
		}

		public PrimitiveType getOutputPrimitiveType() {
			return Money;
		}
		
		public void encode(Type type, StringBuilder buffer, Object val, Context context) throws IOException {			

			buffer.append(val.toString());
		}

	}

	static int getFractionalDigits(Context context) {
		
		Object val = context.getSetting(FIELD_MONEY_FRACTIONAL_DIGITS);
		if(val == null)
			return 2;
		
		return (int)(Integer)val;
	}

}
