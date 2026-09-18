package com.antflow.mobile.workflow;

import com.antflow.authz.AuthorizationService;
import com.antflow.engine.BizException;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MobileAppService {
    private static final int MAX_FAVORITE_APPS = 8;
    private static final String PUBLISHED_STATUS = "PUBLISHED";
    private static final String DEFAULT_CATEGORY = "other";

    private final FormDefinitionMapper formDefinitionMapper;
    private final MobileAppPreferenceMapper preferenceMapper;
    private final ObjectMapper objectMapper;
    private final AuthorizationService authorizationService;

    public List<MobileAppDto> list(long userId, String keyword, String category) {
        if (category != null && !category.isBlank() && !DEFAULT_CATEGORY.equals(category)) {
            return List.of();
        }
        QueryWrapper<FormDefinition> query = publishedFormsQuery();
        if (keyword != null && !keyword.isBlank()) {
            String trimmed = keyword.trim();
            query.and(wrapper -> wrapper.like("name", trimmed)
                .or().like("code", trimmed)
                .or().like("description", trimmed));
        }
        query.orderByDesc("updated_at").orderByDesc("id");
        return usableForms(userId, query).stream()
            .map(MobileAppService::toMobileApp)
            .toList();
    }

    public List<MobileAppDto> favorites(long userId) {
        MobileAppPreference preference = preferenceMapper.selectById(userId);
        if (preference == null) {
            return list(userId, null, null).stream().limit(MAX_FAVORITE_APPS).toList();
        }
        List<Long> formIds = readFormIds(preference.getFormIds());
        if (formIds.isEmpty()) {
            return List.of();
        }
        var publishedForms = usableForms(userId, publishedFormsQuery().in("id", formIds)).stream()
            .collect(java.util.stream.Collectors.toMap(FormDefinition::getId,
                java.util.function.Function.identity()));
        return formIds.stream()
            .map(publishedForms::get)
            .filter(java.util.Objects::nonNull)
            .map(MobileAppService::toMobileApp)
            .toList();
    }

    @Transactional
    public void saveFavorites(long userId, List<Long> requestedFormIds) {
        if (requestedFormIds == null) {
            throw new BizException("INVALID_FAVORITES", "formIds is required");
        }
        if (requestedFormIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new BizException("INVALID_FAVORITES", "formIds cannot contain null");
        }
        List<Long> formIds = new ArrayList<>(new LinkedHashSet<>(requestedFormIds));
        if (formIds.size() != requestedFormIds.size()) {
            throw new BizException("INVALID_FAVORITES", "formIds cannot contain duplicates");
        }
        if (formIds.size() > MAX_FAVORITE_APPS) {
            throw new BizException("TOO_MANY_FAVORITES", "at most 8 apps can be favorited");
        }
        if (!formIds.isEmpty()) {
            if (usableForms(userId, publishedFormsQuery().in("id", formIds)).size()
                    != formIds.size()) {
                throw new BizException("INVALID_FAVORITES", "favorite app is unavailable");
            }
        }

        MobileAppPreference preference = preferenceMapper.selectById(userId);
        boolean isNew = preference == null;
        if (isNew) {
            preference = new MobileAppPreference();
            preference.setUserId(userId);
        }
        preference.setFormIds(writeFormIds(formIds));
        preference.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        if (isNew) {
            preferenceMapper.insert(preference);
        } else {
            preferenceMapper.updateById(preference);
        }
    }

    private QueryWrapper<FormDefinition> publishedFormsQuery() {
        return new QueryWrapper<FormDefinition>().eq("status", PUBLISHED_STATUS);
    }

    private List<FormDefinition> usableForms(long userId, QueryWrapper<FormDefinition> query) {
        // ponytail: catalog is small; move the shared predicate into SQL only if measured growth warrants it.
        return formDefinitionMapper.selectList(query).stream()
            .filter(form -> authorizationService.canUseForm(form.getId(), userId))
            .toList();
    }

    private List<Long> readFormIds(String value) {
        try {
            JsonNode root = objectMapper.readTree(value);
            if (root == null || !root.isArray()) {
                throw new IllegalStateException("mobile app preferences must be a JSON array");
            }
            List<Long> ids = new ArrayList<>();
            root.forEach(node -> {
                if (!node.canConvertToLong()) {
                    throw new IllegalStateException("mobile app preference contains an invalid form id");
                }
                ids.add(node.longValue());
            });
            return ids;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid mobile app preferences", exception);
        }
    }

    private String writeFormIds(List<Long> formIds) {
        try {
            return objectMapper.writeValueAsString(formIds);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not serialize mobile app preferences", exception);
        }
    }

    static MobileAppDto toMobileApp(FormDefinition formDefinition) {
        return new MobileAppDto(
            formDefinition.getId(),
            formDefinition.getCode(),
            formDefinition.getName(),
            null,
            DEFAULT_CATEGORY,
            "其他",
            formDefinition.getDescription());
    }
}
