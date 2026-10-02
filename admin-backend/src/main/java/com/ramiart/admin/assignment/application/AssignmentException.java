package com.ramiart.admin.assignment.application;

import java.util.List;
import com.ramiart.admin.assignment.application.AssignmentModels.ConflictView;

public final class AssignmentException extends RuntimeException {
    private final String code;
    private final List<ConflictView> conflicts;

    public AssignmentException(String code) { this(code, List.of()); }
    public AssignmentException(String code, List<ConflictView> conflicts) {
        super(code); this.code = code; this.conflicts = List.copyOf(conflicts);
    }
    public String code() { return code; }
    public List<ConflictView> conflicts() { return conflicts; }
}
