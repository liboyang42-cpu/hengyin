package com.chengyinhub.business.marketplace.paid.materialization;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.fasterxml.jackson.core.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** NEW closed field adapter for the EXISTING installed w18-member-text-v1 component, whose
 * validationMethod=1 is explicit. No license, target, payment, mode or publication authority here.
 * No generic BeanUtils/JSON-to-entity copy. The caller must verify the durable source and preserve
 * the original component/source/terms alongside the actual generated CMS_MEMBER_TEMPLATE ID. */
public final class PaidTemplateMaterializationFields {
    public static final String SCHEMA="w18-paid-professional-text-fields-v1";
    private static final Set<String> TEXT=Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "title","description","ruleInstructions","merchantGuide","questionName","questionAnswer","hint1","hint2","answerReveal")));
    private final Map<String,String> fields;
    private final byte[] original;
    private PaidTemplateMaterializationFields(Map<String,String> fields,byte[] original){this.fields=Collections.unmodifiableMap(new LinkedHashMap<String,String>(fields));this.original=original.clone();}
    public static PaidTemplateMaterializationFields decode(byte[] bytes){
        if(bytes==null||bytes.length==0||bytes.length>1048576)throw rejected("component","size");
        String text;
        try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException invalid){throw rejected("component","encoding");}
        Map<String,String> fields=new LinkedHashMap<String,String>();Set<String> seen=new HashSet<String>();
        try(JsonParser parser=new JsonFactory().createParser(text)){
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);expect(parser.nextToken(),JsonToken.START_OBJECT,"component");
            while(parser.nextToken()!=JsonToken.END_OBJECT){
                expect(parser.currentToken(),JsonToken.FIELD_NAME,"component");String field=parser.currentName();seen.add(field);JsonToken token=parser.nextToken();
                if(TEXT.contains(field)){expect(token,JsonToken.VALUE_STRING,field);String value=parser.getText();utf16(value,field);fields.put(field,value);}
                else if("schema".equals(field)){expect(token,JsonToken.VALUE_STRING,field);if(!"w18-member-text-v1".equals(parser.getText()))throw rejected(field,"schema");}
                else if("validationMethod".equals(field)){expect(token,JsonToken.VALUE_NUMBER_INT,field);if(parser.getIntValue()!=1)throw rejected(field,"unsupported-method");}
                else if("bindings".equals(field)){expect(token,JsonToken.START_OBJECT,field);expect(parser.nextToken(),JsonToken.END_OBJECT,field);}
                else throw rejected(field,"unmapped");
            }
            if(parser.nextToken()!=null||!seen.containsAll(Arrays.asList("schema","validationMethod","bindings","title")))throw rejected("component","missing-required-field");
        }catch(IOException invalid){throw rejected("component","invalid-json");}
        if(blank(fields.get("title")))throw rejected("title","empty");
        // Existing professional UI limits the short description to 30 UTF-16 units. Never truncate.
        if(fields.get("description")!=null&&fields.get("description").length()>30)throw rejected("description","exceeds-30-utf16");
        return new PaidTemplateMaterializationFields(fields,bytes);
    }
    /** Creates an unsaved actual domain row. Only the transaction-owning materializer may insert it.
     * No title/player-count/duration/category/reward defaults are invented to pass publication. */
    public CmsMemberTemplate newPrivateDraft(long actor,Date at){
        if(actor<=0||at==null)throw rejected("owner","missing");CmsMemberTemplate row=new CmsMemberTemplate();
        row.setMemberId(actor);row.setTitle(fields.get("title"));row.setDescription(fields.get("description"));
        row.setRuleInstructions(fields.get("ruleInstructions"));row.setMerchantGuide(fields.get("merchantGuide"));
        row.setQuestionName(fields.get("questionName"));row.setQuestionAnswer(fields.get("questionAnswer"));
        row.setHint1(fields.get("hint1"));row.setHint2(fields.get("hint2"));row.setAnswerReveal(fields.get("answerReveal"));
        row.setValidationMethod(1);row.setOriginalTemplateId(0L);row.setIsSync(0);row.setDraftStatus(0);row.setStatus(0L);row.setCheckStatus(0);row.setPublishStatus(0L);row.setDelFlag(0);
        row.setMerchantNodeEnabled(0);row.setNodeSubmitStatus(0);row.setXpValue(0);row.setCreateBy(String.valueOf(actor));row.setUpdateBy(String.valueOf(actor));row.setCreateTime(new Date(at.getTime()));row.setUpdateTime(new Date(at.getTime()));
        return row;
    }
    /** Must run on real DB readback before commit, including optional field absence. */
    public void requireExactReadback(CmsMemberTemplate row,long actor){
        if(row==null||row.getId()==null||row.getId()<=0||!Long.valueOf(actor).equals(row.getMemberId())
                || !Integer.valueOf(1).equals(row.getValidationMethod())||!Integer.valueOf(0).equals(row.getIsSync())
                || !Integer.valueOf(0).equals(row.getDraftStatus())||!Long.valueOf(0).equals(row.getStatus())
                || !Integer.valueOf(0).equals(row.getCheckStatus())||!Long.valueOf(0).equals(row.getPublishStatus())
                || !Integer.valueOf(0).equals(row.getDelFlag())||!Long.valueOf(0).equals(row.getOriginalTemplateId()))throw rejected("template","readback-state");
        Map<String,String> actual=new LinkedHashMap<String,String>();actual.put("title",row.getTitle());actual.put("description",row.getDescription());actual.put("ruleInstructions",row.getRuleInstructions());actual.put("merchantGuide",row.getMerchantGuide());actual.put("questionName",row.getQuestionName());actual.put("questionAnswer",row.getQuestionAnswer());actual.put("hint1",row.getHint1());actual.put("hint2",row.getHint2());actual.put("answerReveal",row.getAnswerReveal());
        for(String field:TEXT)if(!Objects.equals(fields.get(field),actual.get(field)))throw rejected(field,"readback-loss");
        if(row.getRewardRulesJson()!=null||row.getCouponId()!=null||row.getMedalName()!=null||row.getMedalImg()!=null||row.getFeedbackText()!=null
                ||row.getCategoryId()!=null||row.getActivityCategoryids()!=null||row.getPlayers()!=null||row.getUsageLocation()!=null||row.getRequiredMaterials()!=null||row.getDifficulty()!=null
                ||row.getQuestionA()!=null||row.getQuestionB()!=null||row.getQuestionC()!=null||row.getQuestionD()!=null||row.getCorrectAnswer()!=null
                ||row.getSensorType()!=null||row.getPhotoRequireDesc()!=null||row.getInteractionType()!=null
                ||row.getXpValue()!=null&&row.getXpValue()!=0||row.getDuration()!=null&&row.getDuration()!=0
                || row.getImgUrl()!=null||row.getQuestionImg()!=null||row.getQuestionAudio()!=null||row.getQuestionOptionMediaJson()!=null
                || row.getAdvancedConfigJson()!=null||row.getPreferenceJson()!=null||row.getSensorConfig()!=null
                || row.getStoryJson()!=null||row.getStoryText()!=null||row.getStoryImg()!=null||row.getAudioUrl()!=null
                || !Integer.valueOf(0).equals(row.getMerchantNodeEnabled())||!Integer.valueOf(0).equals(row.getNodeSubmitStatus()))throw rejected("template","unexpected-capability");
    }
    public byte[] originalComponent(){return original.clone();}
    public Map<String,String> textFields(){return fields;}
    /** Diagnostics for this incomplete private draft, not a substitute for the real publishing validator. */
    public List<String> missingProfessionalInputs(){
        List<String> missing=new ArrayList<String>();if(blank(fields.get("description")))missing.add("description");
        missing.add("players");missing.add("duration");missing.add("category");
        if(blank(fields.get("questionName")))missing.add("questionName");if(blank(fields.get("questionAnswer")))missing.add("questionAnswer");return Collections.unmodifiableList(missing);
    }
    private static boolean blank(String text){return text==null||text.trim().isEmpty();}
    private static void utf16(String value,String field){for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))throw rejected(field,"encoding");}else if(Character.isLowSurrogate(c))throw rejected(field,"encoding");}}
    private static void expect(JsonToken actual,JsonToken wanted,String field){if(actual!=wanted)throw rejected(field,"shape");}
    private static IllegalArgumentException rejected(String field,String reason){return new IllegalArgumentException("W18_MATERIALIZATION_FIELD_REJECTED:"+(field==null?"unknown":field.substring(0,Math.min(field.length(),128)))+":"+reason);}
}
