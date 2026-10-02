package com.ramiart.admin.schedule.application;
import java.util.Map;
public final class ScheduleException extends RuntimeException {
  private final String code; private final Map<String,Object> details;
  public ScheduleException(String code){this(code,Map.of());}
  public ScheduleException(String code,Map<String,Object> details){super(code);this.code=code;this.details=Map.copyOf(details);}
  public String code(){return code;} public Map<String,Object> details(){return details;}
}
