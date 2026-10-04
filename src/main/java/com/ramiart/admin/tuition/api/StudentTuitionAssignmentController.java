package com.ramiart.admin.tuition.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.tuition.application.StudentTuitionAssignmentException;
import com.ramiart.admin.tuition.application.StudentTuitionAssignmentModels.*;
import com.ramiart.admin.tuition.application.StudentTuitionAssignmentService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class StudentTuitionAssignmentController {
    private static final Logger LOG=LoggerFactory.getLogger(StudentTuitionAssignmentController.class);
    private final StudentTuitionAssignmentService service;
    public StudentTuitionAssignmentController(StudentTuitionAssignmentService service){this.service=service;}
    @GetMapping("/api/admin/students/{studentId}/tuition-assignments") ResponseEntity<ApiEnvelope<?>> list(@PathVariable UUID studentId,Authentication auth,HttpServletRequest r){return ok(service.list(studentId,auth),r);}
    @GetMapping("/api/admin/students/{studentId}/tuition-assignment-candidates") ResponseEntity<ApiEnvelope<?>> candidates(@PathVariable UUID studentId,@RequestParam(required=false) LocalDate effectiveFrom,Authentication auth,HttpServletRequest r){return ok(service.candidates(studentId,effectiveFrom,auth),r);}
    @PostMapping("/api/admin/students/{studentId}/tuition-assignments") ResponseEntity<ApiEnvelope<?>> create(@PathVariable UUID studentId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Write body,Authentication auth,HttpServletRequest r){requireWrite(auth);var result=service.create(studentId,body,actor(auth),key,meta(r));return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(result.data(),RequestIdFilter.get(r)));}
    @PutMapping("/api/admin/student-tuition-assignments/{assignmentId}") ResponseEntity<ApiEnvelope<?>> update(@PathVariable UUID assignmentId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Update body,Authentication auth,HttpServletRequest r){requireWrite(auth);return ok(service.update(assignmentId,body,actor(auth),key,meta(r)),r);}
    private static UUID actor(Authentication a){return UUID.fromString(a.getName());}
    private static void requireWrite(Authentication a){if(a==null||!a.getAuthorities().stream().anyMatch(x->x.getAuthority().equals("STUDENT_TUITION_WRITE")))throw new StudentTuitionAssignmentException("STUDENT_TUITION_WRITE_DENIED");}
    private static Metadata meta(HttpServletRequest r){String ua=r.getHeader(HttpHeaders.USER_AGENT);if(ua!=null)ua=ua.replaceAll("[\\p{Cntrl}]","");if(ua!=null&&ua.length()>512)ua=ua.substring(0,512);return new Metadata(RequestIdFilter.get(r),r.getRemoteAddr(),ua);}
    private static <T> ResponseEntity<ApiEnvelope<?>> ok(T v,HttpServletRequest r){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(v,RequestIdFilter.get(r)));}
    @ExceptionHandler(StudentTuitionAssignmentException.class) ResponseEntity<?> error(StudentTuitionAssignmentException e,HttpServletRequest r){String c=e.code();int status=switch(c){case "STUDENT_NOT_FOUND","TUITION_POLICY_ITEM_NOT_FOUND","STUDENT_TUITION_ASSIGNMENT_NOT_FOUND"->404;case "STUDENT_TUITION_PERIOD_CONFLICT","STUDENT_TUITION_VERSION_CONFLICT","IDEMPOTENCY_KEY_REUSED","IDEMPOTENCY_IN_PROGRESS"->409;case "STUDENT_TUITION_READ_DENIED","STUDENT_TUITION_WRITE_DENIED"->403;case "STUDENT_STATUS_NOT_ASSIGNABLE","TUITION_LESSON_COUNT_MISMATCH","TUITION_ASSIGNMENT_BILLED_PERIOD","TUITION_OVERRIDE_REASON_REQUIRED"->422;default->400;};java.util.Map<String,Object> error=new java.util.LinkedHashMap<>();error.put("code",c);error.put("message","원생 수업료 적용 요청을 처리할 수 없습니다.");if(!e.details().isEmpty())error.put("details",e.details());java.util.Map<String,Object> body=new java.util.LinkedHashMap<>();body.put("success",false);body.put("data",null);body.put("error",error);body.put("requestId",RequestIdFilter.get(r));return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);}
    @ExceptionHandler(DataAccessException.class) ResponseEntity<ApiEnvelope<Void>> persistence(DataAccessException e,HttpServletRequest r){LOG.error("Student tuition assignment persistence failed: requestId={}, method={}, path={}",RequestIdFilter.get(r),r.getMethod(),r.getRequestURI());return ResponseEntity.internalServerError().cacheControl(CacheControl.noStore()).body(ApiEnvelope.failure("STUDENT_TUITION_SAVE_FAILED","원생 수업료 적용 요청을 처리하지 못했습니다.",List.of(),RequestIdFilter.get(r)));}
}
