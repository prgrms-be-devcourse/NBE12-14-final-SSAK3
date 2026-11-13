package com.csmatch.question.dto;

/** 문제 생성의 근거가 되는 개념. 엔티티 대신 값만 담아 세션 밖에서도 안전하게 쓴다. */
public record ConceptSource(Integer conceptId, String name, String description, String categoryName) {
}
