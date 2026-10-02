package com.ramiart.admin.student.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.student.application.StudentException;
import com.ramiart.admin.student.application.StudentModels.*;
import com.ramiart.admin.student.application.StudentService;
import com.ramiart.admin.student.application.StudentService.RequestMetadata;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public final class StudentController {
    private final StudentService service;
    public StudentController(StudentService service){this.service=service;}

    @GetMapping("/students")
    ResponseEntity<ApiEnvelope<StudentPage>> list(@RequestParam(required=false)String keyword,
            @RequestParam(required=false)String statuses,@RequestParam(required=false)LocalDate joinedFrom,
            @RequestParam(required=false)LocalDate joinedTo,@RequestParam(required=false)String birthdayFrom,
            @RequestParam(required=false)String birthdayTo,@RequestParam(defaultValue="0")int page,
            @RequestParam(defaultValue="20")int size,@RequestParam(defaultValue="studentName,asc")String sort,
            HttpServletRequest request){return ok(service.list(keyword,statuses,joinedFrom,joinedTo,birthdayFrom,birthdayTo,page,size,sort),request);}
    @GetMapping("/students/{id}") ResponseEntity<ApiEnvelope<StudentDetail>> detail(@PathVariable UUID id,HttpServletRequest request){return ok(service.detail(id),request);}
    @PostMapping("/students") ResponseEntity<ApiEnvelope<StudentDetail>> create(@RequestHeader("Idempotency-Key")UUID key,@RequestBody StudentCreate body,Authentication auth,HttpServletRequest request){StudentDetail data=service.create(body,actor(auth),key,meta(request));return ResponseEntity.status(201).header(HttpHeaders.LOCATION,"/admin/students/"+data.id()).cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(data,RequestIdFilter.get(request)));}
    @PutMapping("/students/{id}") ResponseEntity<ApiEnvelope<StudentDetail>> update(@PathVariable UUID id,@RequestHeader("Idempotency-Key")UUID key,@RequestBody StudentUpdate body,Authentication auth,HttpServletRequest request){return ok(service.update(id,body,actor(auth),key,meta(request)),request);}
    @PostMapping("/students/{id}/status-change-previews") ResponseEntity<ApiEnvelope<StatusPreview>> preview(@PathVariable UUID id,@RequestBody StatusWrite body,HttpServletRequest request){return ok(service.preview(id,body),request);}
    @PostMapping("/students/{id}/status-changes") ResponseEntity<ApiEnvelope<StatusChange>> status(@PathVariable UUID id,@RequestHeader("Idempotency-Key")UUID key,@RequestBody StatusWrite body,Authentication auth,HttpServletRequest request){StatusChange data=service.changeStatus(id,body,actor(auth),key,meta(request));return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(data,RequestIdFilter.get(request)));}

    @GetMapping("/students/{id}/notes") ResponseEntity<ApiEnvelope<NotePage>> notes(@PathVariable UUID id,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="5")int size,Authentication auth,HttpServletRequest request){return ok(service.notes(id,cursor,size,actor(auth)),request);}
    @PostMapping("/students/{id}/notes") ResponseEntity<ApiEnvelope<NoteView>> noteCreate(@PathVariable UUID id,@RequestHeader("Idempotency-Key")UUID key,@RequestBody NoteCreate body,Authentication auth,HttpServletRequest request){NoteView data=service.createNote(id,body,actor(auth),key,meta(request));return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(data,RequestIdFilter.get(request)));}
    @PutMapping("/student-notes/{id}") ResponseEntity<ApiEnvelope<NoteView>> noteUpdate(@PathVariable UUID id,@RequestBody NoteUpdate body,Authentication auth,HttpServletRequest request){return ok(service.updateNote(id,body,actor(auth),meta(request)),request);}
    @DeleteMapping("/student-notes/{id}") ResponseEntity<Void> noteHide(@PathVariable UUID id,@RequestBody NoteHide body,Authentication auth,HttpServletRequest request){service.hideNote(id,body,actor(auth),meta(request));return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();}

    @ExceptionHandler(StudentException.class)
    ResponseEntity<?> error(StudentException exception,HttpServletRequest request){HttpStatus status=switch(exception.code()){case "STUDENT_NOT_FOUND","STUDENT_NOTE_NOT_FOUND"->HttpStatus.NOT_FOUND;case "STUDENT_DUPLICATE_CANDIDATE","STUDENT_VERSION_CONFLICT","STUDENT_NOTE_VERSION_CONFLICT","IDEMPOTENCY_KEY_REUSED","IDEMPOTENCY_IN_PROGRESS"->HttpStatus.CONFLICT;case "GUARDIAN_REQUIRED","PRIMARY_GUARDIAN_REQUIRED","GUARDIAN_PHONE_DUPLICATED","GUARDIAN_REFERENCED","STUDENT_STATUS_TRANSITION_DENIED","STUDENT_STATUS_DATE_INVALID","STUDENT_STATUS_PREVIEW_REQUIRED","STUDENT_NOTE_EDIT_WINDOW_EXPIRED"->HttpStatus.UNPROCESSABLE_CONTENT;case "STUDENT_NOTE_AUTHOR_REQUIRED"->HttpStatus.FORBIDDEN;default->HttpStatus.BAD_REQUEST;};String message=switch(exception.code()){case "STUDENT_DUPLICATE_CANDIDATE"->"중복 가능성이 있는 원생이 있습니다.";case "STUDENT_NOT_FOUND"->"원생을 찾을 수 없습니다.";case "STUDENT_VERSION_CONFLICT","STUDENT_NOTE_VERSION_CONFLICT"->"다른 관리자가 먼저 변경했습니다.";case "STUDENT_STATUS_PREVIEW_REQUIRED"->"상태 변경 영향을 다시 확인해 주세요.";case "STUDENT_NOTE_EDIT_WINDOW_EXPIRED"->"메모 수정 가능 시간이 지났습니다.";default->"입력값을 확인해 주세요.";};Map<String,Object> error=new LinkedHashMap<>();error.put("code",exception.code());error.put("message",message);if(!exception.details().isEmpty())error.put("details",exception.details());return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("success",false,"error",error,"requestId",RequestIdFilter.get(request)));}
    private static UUID actor(Authentication a){return UUID.fromString(a.getName());}
    private static RequestMetadata meta(HttpServletRequest r){String ua=r.getHeader(HttpHeaders.USER_AGENT);if(ua!=null)ua=ua.replaceAll("[\\p{Cntrl}]","");if(ua!=null&&ua.length()>512)ua=ua.substring(0,512);return new RequestMetadata(RequestIdFilter.get(r),r.getRemoteAddr(),ua);}
    private static <T> ResponseEntity<ApiEnvelope<T>> ok(T data,HttpServletRequest request){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(data,RequestIdFilter.get(request)));}
}
