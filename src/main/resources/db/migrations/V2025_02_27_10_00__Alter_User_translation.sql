ALTER TABLE User MODIFY COLUMN language ENUM('DE','EN','ES','FR','RW')  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL;

ALTER TABLE FacilityTranslation MODIFY COLUMN language ENUM('DE','EN','ES','FR','RW')  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL;
ALTER TABLE SemiProductTranslation MODIFY COLUMN language ENUM('DE','EN','ES','FR','RW')  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL;
ALTER TABLE ProcessingEvidenceFieldTranslation MODIFY COLUMN language ENUM('DE','EN','ES','FR','RW')  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL;
ALTER TABLE ProcessingEvidenceTypeTranslation MODIFY COLUMN language ENUM('DE','EN','ES','FR','RW')  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL;
ALTER TABLE ProductTypeTranslation MODIFY COLUMN language ENUM('DE','EN','ES','FR','RW')  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL;