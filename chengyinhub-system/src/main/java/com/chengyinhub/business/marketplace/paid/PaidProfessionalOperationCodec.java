package com.chengyinhub.business.marketplace.paid;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Arrays;

/** New internal canonical binary command record, never an entitlement. Fixed field order/count,
 * bounded UTF-8 lengths and no recursive parser. It stores no source body, terms or credentials. */
final class PaidProfessionalOperationCodec {
    private static final String FORMAT="w18-paid-professional-operation-record-v1";
    private PaidProfessionalOperationCodec(){}
    static byte[] encode(PaidProfessionalMaterializationCommand c){
        if(c==null)throw invalid();
        try{
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
            for(String value:new String[]{FORMAT,c.requestId,c.installationRequestId,Long.toString(c.ownedDraftId),c.licenseId,c.moduleId,c.purchasedVersionId,
                    c.contentHash,c.termsHash,c.componentHash,Long.toString(c.currentDraftRevision),c.currentDraftPayloadHash,
                    Long.toString(c.targetTopicId),c.targetOwnerModeFingerprint,c.confirmedMode.name()}){
                byte[] data=value.getBytes(StandardCharsets.UTF_8);out.writeInt(data.length);out.write(data);
            }
            byte[] data=bytes.toByteArray();if(data.length>4096)throw invalid();return data;
        }catch(IOException impossible){throw new IllegalStateException(impossible);}
    }
    static PaidProfessionalMaterializationCommand decode(byte[] bytes){
        if(bytes==null||bytes.length==0||bytes.length>4096)throw invalid();
        try{
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));if(!FORMAT.equals(text(in)))throw invalid();
            PaidProfessionalMaterializationCommand command=new PaidProfessionalMaterializationCommand(text(in),text(in),number(in),text(in),text(in),text(in),
                    text(in),text(in),text(in),number(in),text(in),number(in),text(in),PaidProfessionalMaterializationCommand.Mode.valueOf(text(in)));
            if(in.read()!=-1||!Arrays.equals(encode(command),bytes))throw invalid();return command;
        }catch(IOException|IllegalArgumentException error){throw invalid();}
    }
    private static String text(DataInputStream in)throws IOException{
        int size=in.readInt();if(size<0||size>1024||size>in.available())throw invalid();byte[] bytes=new byte[size];in.readFully(bytes);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static long number(DataInputStream in)throws IOException{String value=text(in);if(!value.matches("[1-9][0-9]{0,18}"))throw invalid();return Long.parseLong(value);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("PAID_PROFESSIONAL_OPERATION_RECORD_UNAVAILABLE");}
}
