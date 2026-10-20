package com.chengyinhub.business.marketplace.paid;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
class PaidProfessionalOperationCodecTest {
 private PaidProfessionalMaterializationCommand command(){String h=new String(new char[64]).replace('\0','a');return new PaidProfessionalMaterializationCommand("10000000-0000-4000-8000-000000000001","00000000-0000-4000-8000-000000000001",1,"w18-paid-11","模块","版一",h,h,h,1,h,81,h,PaidProfessionalMaterializationCommand.Mode.CITY_ORIENTATION);}
 @Test void canonicalBinaryRoundTripPreservesAllConfirmedFields(){PaidProfessionalMaterializationCommand c=command();byte[] b=PaidProfessionalOperationCodec.encode(c);assertEquals(c.digest(),PaidProfessionalOperationCodec.decode(b).digest());assertArrayEquals(b,PaidProfessionalOperationCodec.encode(PaidProfessionalOperationCodec.decode(b)));}
 @Test void hugeDeclaredLengthTrailingAndTruncatedInputFailBeforeAllocation(){byte[] b=PaidProfessionalOperationCodec.encode(command());for(byte[] invalid:new byte[][]{new byte[]{127,127,127,127},Arrays.copyOf(b,3),Arrays.copyOf(b,b.length-1),Arrays.copyOf(b,b.length+1),new byte[4097]})assertThrows(IllegalArgumentException.class,()->PaidProfessionalOperationCodec.decode(invalid));}
 @Test void unknownFormatAndMalformedUtf8NeverNormalizeIntoValidCommand(){byte[] b=PaidProfessionalOperationCodec.encode(command());b[4]='X';assertThrows(IllegalArgumentException.class,()->PaidProfessionalOperationCodec.decode(b));byte[] utf8=PaidProfessionalOperationCodec.encode(command());utf8[4]=(byte)0xc0;assertThrows(IllegalArgumentException.class,()->PaidProfessionalOperationCodec.decode(utf8));}
}
