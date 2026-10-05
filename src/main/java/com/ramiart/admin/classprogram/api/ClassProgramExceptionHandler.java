package com.ramiart.admin.classprogram.api;

import com.ramiart.admin.classprogram.application.ClassProgramException;
import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses=ClassProgramController.class)
public class ClassProgramExceptionHandler {
    private static final Logger LOG=LoggerFactory.getLogger(ClassProgramExceptionHandler.class);
    @ExceptionHandler(ClassProgramException.class) ResponseEntity<ApiEnvelope<Void>> handle(ClassProgramException e,HttpServletRequest r){HttpStatus s=switch(e.code()){case "VALIDATION_ERROR"->HttpStatus.BAD_REQUEST;case "CLASS_PROGRAM_COURSE_NOT_FOUND"->HttpStatus.NOT_FOUND;case "CLASS_PROGRAM_VERSION_CONFLICT","CLASS_PROGRAM_DRAFT_EXISTS","CLASS_PROGRAM_ORDER_CONFLICT","IDEMPOTENCY_KEY_REUSED","IDEMPOTENCY_IN_PROGRESS"->HttpStatus.CONFLICT;case "CLASS_PROGRAM_INCOMPLETE","CLASS_PROGRAM_VISIBLE_REQUIRED"->HttpStatus.UNPROCESSABLE_ENTITY;default->HttpStatus.FORBIDDEN;};List<ApiEnvelope.FieldError> f=e.field()==null?List.of():List.of(new ApiEnvelope.FieldError(e.field(),e.code(),"입력값을 확인해 주세요."));return ResponseEntity.status(s).cacheControl(org.springframework.http.CacheControl.noStore()).body(ApiEnvelope.failure(e.code(),"수업 프로그램 요청을 처리하지 못했습니다.",f,RequestIdFilter.get(r)));}
    @ExceptionHandler(DataAccessException.class) ResponseEntity<ApiEnvelope<Void>> persistence(DataAccessException e,HttpServletRequest r){LOG.error("Class program persistence failed: requestId={}, method={}, path={}",RequestIdFilter.get(r),r.getMethod(),r.getRequestURI());return ResponseEntity.status(500).cacheControl(org.springframework.http.CacheControl.noStore()).body(ApiEnvelope.failure("CLASS_PROGRAM_SAVE_FAILED","수업 프로그램 요청을 처리하지 못했습니다.",List.of(),RequestIdFilter.get(r)));}
}
