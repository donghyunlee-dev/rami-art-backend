package com.rami.artstudio.classes.service;

import java.util.List;
import java.util.UUID;

import com.rami.artstudio.classes.dto.ClassDtos.ClassView;
import com.rami.artstudio.classes.dto.ClassDtos.UpdateSortRequest;
import com.rami.artstudio.classes.dto.ClassDtos.UpsertClassRequest;

public interface ClassService {
    List<ClassView> getPublicClasses();

    ClassView getPublicClass(UUID classId);

    List<ClassView> getAdminClasses();

    ClassView createClass(UpsertClassRequest request);

    ClassView updateClass(UUID classId, UpsertClassRequest request);

    void deleteClass(UUID classId);

    ClassView updateSortOrder(UUID classId, UpdateSortRequest request);
}
