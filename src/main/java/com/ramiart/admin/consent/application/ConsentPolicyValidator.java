package com.ramiart.admin.consent.application;

import java.util.Set;

public final class ConsentPolicyValidator {
    private static final Set<String> TYPES=Set.of("PERSONAL_DATA_REQUIRED","MEDIA_PUBLICATION","PORTRAIT","OPTIONAL_NOTIFICATION");

    private ConsentPolicyValidator(){}

    public record PolicyDraft(String title,String body,boolean required,Integer validDays,boolean evidenceRequired){}

    public static void validate(String type,PolicyDraft draft){
        if(type==null||!TYPES.contains(type)||draft==null||draft.title()==null||draft.body()==null
                ||draft.title().trim().isEmpty()||draft.title().trim().length()>200
                ||draft.body().trim().isEmpty()||draft.body().trim().length()>20_000
                ||draft.validDays()!=null&&(draft.validDays()<1||draft.validDays()>3650)){
            throw new IllegalArgumentException("VALIDATION_ERROR");
        }
        if("PERSONAL_DATA_REQUIRED".equals(type)&&!draft.required())throw new IllegalArgumentException("CONSENT_POLICY_REQUIRED");
    }
}
