package com.rami.artstudio.classes.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rami.artstudio.classes.domain.ArtClass;
import com.rami.artstudio.classes.domain.ClassTag;
import com.rami.artstudio.classes.dto.ClassDtos.ClassView;
import com.rami.artstudio.classes.dto.ClassDtos.UpdateSortRequest;
import com.rami.artstudio.classes.dto.ClassDtos.UpsertClassRequest;
import com.rami.artstudio.classes.infra.ArtClassRepository;
import com.rami.artstudio.classes.infra.ClassTagRepository;
import com.rami.artstudio.common.exception.ConflictException;

@Service
@Transactional
public class ClassServiceImpl implements ClassService {

    private final ArtClassRepository artClassRepository;
    private final ClassTagRepository classTagRepository;

    public ClassServiceImpl(
            ArtClassRepository artClassRepository,
            ClassTagRepository classTagRepository) {
        this.artClassRepository = artClassRepository;
        this.classTagRepository = classTagRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ClassView> getPublicClasses() {
        return toViews(artClassRepository.findAllByIsActiveTrueAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc());
    }

    @Override
    @Transactional(readOnly = true)
    public ClassView getPublicClass(UUID classId) {
        ArtClass artClass = artClassRepository.findByIdAndIsActiveTrueAndDeletedAtIsNull(classId)
                .orElseThrow(() -> new NoSuchElementException("수업 정보를 찾을 수 없습니다."));
        return toView(artClass, loadTags(List.of(artClass.getId())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ClassView> getAdminClasses() {
        return toViews(artClassRepository.findAllByDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc());
    }

    @Override
    public ClassView createClass(UpsertClassRequest request) {
        if (artClassRepository.existsByAgeGroupAndDeletedAtIsNull(request.ageGroup())) {
            throw new ConflictException("같은 연령 그룹의 수업이 이미 존재합니다.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        ArtClass artClass = new ArtClass();
        artClass.setId(UUID.randomUUID());
        artClass.setAgeGroup(request.ageGroup());
        artClass.setAgeRange(request.ageRange());
        artClass.setTitle(request.title());
        artClass.setDescription(request.description());
        artClass.setCurriculum(request.curriculum());
        artClass.setThumbnailUrl(request.thumbnailUrl());
        artClass.setSortOrder(request.sortOrder());
        artClass.setIsActive(request.isActive());
        artClass.setCreatedAt(now);
        artClass.setUpdatedAt(now);

        ArtClass saved = artClassRepository.save(artClass);
        replaceTags(saved.getId(), request.tags());
        return toView(saved, loadTags(List.of(saved.getId())));
    }

    @Override
    public ClassView updateClass(UUID classId, UpsertClassRequest request) {
        ArtClass artClass = artClassRepository.findByIdAndDeletedAtIsNull(classId)
                .orElseThrow(() -> new NoSuchElementException("수업 정보를 찾을 수 없습니다."));

        if (artClassRepository.existsByAgeGroupAndDeletedAtIsNullAndIdNot(request.ageGroup(), classId)) {
            throw new ConflictException("같은 연령 그룹의 수업이 이미 존재합니다.");
        }

        artClass.setAgeGroup(request.ageGroup());
        artClass.setAgeRange(request.ageRange());
        artClass.setTitle(request.title());
        artClass.setDescription(request.description());
        artClass.setCurriculum(request.curriculum());
        artClass.setThumbnailUrl(request.thumbnailUrl());
        artClass.setSortOrder(request.sortOrder());
        artClass.setIsActive(request.isActive());
        artClass.setUpdatedAt(OffsetDateTime.now());

        ArtClass saved = artClassRepository.save(artClass);
        replaceTags(saved.getId(), request.tags());
        return toView(saved, loadTags(List.of(saved.getId())));
    }

    @Override
    public void deleteClass(UUID classId) {
        ArtClass artClass = artClassRepository.findByIdAndDeletedAtIsNull(classId)
                .orElseThrow(() -> new NoSuchElementException("수업 정보를 찾을 수 없습니다."));
        artClass.setDeletedAt(OffsetDateTime.now());
        artClass.setUpdatedAt(OffsetDateTime.now());
        artClassRepository.save(artClass);
    }

    @Override
    public ClassView updateSortOrder(UUID classId, UpdateSortRequest request) {
        ArtClass artClass = artClassRepository.findByIdAndDeletedAtIsNull(classId)
                .orElseThrow(() -> new NoSuchElementException("수업 정보를 찾을 수 없습니다."));
        artClass.setSortOrder(request.sortOrder());
        artClass.setUpdatedAt(OffsetDateTime.now());
        ArtClass saved = artClassRepository.save(artClass);
        return toView(saved, loadTags(List.of(saved.getId())));
    }

    private void replaceTags(UUID classId, List<String> tags) {
        classTagRepository.deleteByClassId(classId);

        List<String> sanitizedTags = sanitizeTags(tags);
        List<ClassTag> entities = new ArrayList<>();
        for (int i = 0; i < sanitizedTags.size(); i++) {
            ClassTag classTag = new ClassTag();
            classTag.setId(UUID.randomUUID());
            classTag.setClassId(classId);
            classTag.setTag(sanitizedTags.get(i));
            classTag.setSortOrder(i + 1);
            entities.add(classTag);
        }
        classTagRepository.saveAll(entities);
    }

    private List<String> sanitizeTags(List<String> tags) {
        List<String> sanitizedTags = new ArrayList<>(tags.stream()
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        if (sanitizedTags.isEmpty()) {
            throw new IllegalArgumentException("태그는 1개 이상 필요합니다.");
        }
        return sanitizedTags;
    }

    private List<ClassView> toViews(List<ArtClass> classes) {
        Map<UUID, List<String>> tagsByClassId = loadTags(classes.stream()
                .map(ArtClass::getId)
                .toList());
        return classes.stream()
                .map(artClass -> toView(artClass, tagsByClassId))
                .toList();
    }

    private Map<UUID, List<String>> loadTags(List<UUID> classIds) {
        if (classIds.isEmpty()) {
            return Map.of();
        }

        return classTagRepository.findAllByClassIds(classIds).stream()
                .collect(Collectors.groupingBy(
                        ClassTag::getClassId,
                        Collectors.mapping(ClassTag::getTag, Collectors.toList())));
    }

    private ClassView toView(ArtClass artClass, Map<UUID, List<String>> tagsByClassId) {
        return new ClassView(
                artClass.getId().toString(),
                artClass.getAgeGroup(),
                artClass.getAgeRange(),
                artClass.getTitle(),
                artClass.getDescription(),
                artClass.getCurriculum(),
                artClass.getThumbnailUrl(),
                artClass.getSortOrder(),
                artClass.getIsActive(),
                tagsByClassId.getOrDefault(artClass.getId(), List.of()),
                artClass.getCreatedAt(),
                artClass.getUpdatedAt());
    }
}
