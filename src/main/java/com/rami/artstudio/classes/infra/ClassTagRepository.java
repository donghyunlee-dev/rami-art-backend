package com.rami.artstudio.classes.infra;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.rami.artstudio.classes.domain.ClassTag;

public interface ClassTagRepository extends JpaRepository<ClassTag, UUID> {

    @Query("""
            select ct from ClassTag ct
            where ct.classId in :classIds
            order by ct.classId asc, ct.sortOrder asc, ct.tag asc
            """)
    List<ClassTag> findAllByClassIds(Collection<UUID> classIds);

    void deleteByClassId(UUID classId);
}
