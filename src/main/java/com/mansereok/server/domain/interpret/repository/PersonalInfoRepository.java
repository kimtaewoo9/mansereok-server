package com.mansereok.server.domain.interpret.repository;

import com.mansereok.server.domain.interpret.entity.PersonalInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PersonalInfoRepository extends JpaRepository<PersonalInfo, Long> {

}
