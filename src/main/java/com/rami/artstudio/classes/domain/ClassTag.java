package com.rami.artstudio.classes.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(schema = "rami_art_studio", name = "class_tags")
public class ClassTag {

    @Id
    private UUID id;

    @Column(name = "class_id", nullable = false)
    private UUID classId;

    @Column(nullable = false, length = 100)
    private String tag;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getClassId() {
        return classId;
    }

    public void setClassId(UUID classId) {
        this.classId = classId;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }
}
