package com.ramiart.admin.consent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ConsentPolicyValidatorTest {
    @Test void requiresPersonalDataConsentAndBoundsPolicyFields(){
        ConsentPolicyValidator.PolicyDraft valid=new ConsentPolicyValidator.PolicyDraft("개인정보 처리 동의","개인정보 수집 및 이용에 동의합니다.",false,365,true);

        assertThatThrownBy(()->ConsentPolicyValidator.validate("PERSONAL_DATA_REQUIRED",valid))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("CONSENT_POLICY_REQUIRED");
        assertThatThrownBy(()->ConsentPolicyValidator.validate("OPTIONAL_NOTIFICATION",new ConsentPolicyValidator.PolicyDraft("제목","본문",false,3651,false)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("VALIDATION_ERROR");
    }

    @Test void acceptsNullableExpiryAndValidOptionalPolicy(){
        ConsentPolicyValidator.validate("OPTIONAL_NOTIFICATION",new ConsentPolicyValidator.PolicyDraft("수신 동의","선택 안내를 받습니다.",false,null,false));
        assertThat(true).isTrue();
    }
}
