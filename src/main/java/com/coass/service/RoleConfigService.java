package com.coass.service;

import com.coass.entity.RoleConfig;
import com.coass.repository.RoleConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RoleConfigService {

    private final RoleConfigRepository repository;

    public int getLevel(String roleKey) {
        if (roleKey == null) return 1;
        return repository.findById(roleKey).map(RoleConfig::getPermissionLevel).orElse(1);
    }

    public boolean isAtLeast(String roleKey, String requiredKey) {
        return getLevel(roleKey) >= getLevel(requiredKey);
    }

    public void requireAtLeast(String roleKey, String requiredKey) {
        if (!isAtLeast(roleKey, requiredKey)) {
            throw new AccessDeniedException("Required role: " + requiredKey + ", current: " + roleKey);
        }
    }

    public List<String> rolesUpTo(String roleKey) {
        int level = getLevel(roleKey);
        return repository.findAllByOrderBySortOrderAsc().stream()
                .filter(r -> r.getPermissionLevel() <= level)
                .map(RoleConfig::getKey)
                .toList();
    }

    public List<RoleConfig> getAll() {
        return repository.findAllByOrderBySortOrderAsc();
    }

    @Transactional
    public RoleConfig create(Map<String, Object> body) {
        String key = ((String) body.get("key")).toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        if (repository.existsById(key)) throw new IllegalArgumentException("Role key already exists: " + key);
        RoleConfig rc = new RoleConfig();
        rc.setKey(key);
        rc.setLabel((String) body.get("label"));
        rc.setPermissionLevel(body.get("permissionLevel") instanceof Number n ? n.intValue()
                : Integer.parseInt(body.get("permissionLevel").toString()));
        rc.setSystem(false);
        rc.setDescription((String) body.getOrDefault("description", null));
        rc.setSortOrder(body.get("sortOrder") instanceof Number n ? n.intValue() : 99);
        return repository.save(rc);
    }

    @Transactional
    public RoleConfig update(String key, Map<String, Object> body) {
        RoleConfig rc = repository.findById(key).orElseThrow(() -> new IllegalArgumentException("Role not found: " + key));
        if (body.containsKey("label")) rc.setLabel((String) body.get("label"));
        if (body.containsKey("description")) rc.setDescription((String) body.get("description"));
        if (body.containsKey("sortOrder")) rc.setSortOrder(((Number) body.get("sortOrder")).intValue());
        if (!rc.isSystem() && body.containsKey("permissionLevel"))
            rc.setPermissionLevel(((Number) body.get("permissionLevel")).intValue());
        return repository.save(rc);
    }

    @Transactional
    public void delete(String key) {
        RoleConfig rc = repository.findById(key).orElseThrow(() -> new IllegalArgumentException("Role not found: " + key));
        if (rc.isSystem()) throw new IllegalArgumentException("Cannot delete system role: " + key);
        repository.delete(rc);
    }
}
