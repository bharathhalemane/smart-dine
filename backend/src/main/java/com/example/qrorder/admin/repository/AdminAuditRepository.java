package com.example.qrorder.admin.repository;

import com.example.qrorder.admin.model.AdminAuditEntry;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AdminAuditRepository extends MongoRepository<AdminAuditEntry, String> {

    List<AdminAuditEntry> findFirst100ByTargetIdOrderByAtDescIdDesc(String targetId);
}