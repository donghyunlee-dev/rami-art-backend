package com.ramiart.admin.dashboard.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;

public interface MonthlyDashboardRepository {
    Map<String, Object> tuition(YearMonth month);
    Map<String, Object> attendance(YearMonth month);
    Map<String, Object> lessons(YearMonth month);
    Map<String, Object> enrollment(YearMonth month, ZoneId zone);
    Map<String, Object> capacity(LocalDate asOfDate);
}
