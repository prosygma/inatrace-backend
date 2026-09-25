-- Farmers gained a supervisor review state (PENDING / VALIDATED / REJECTED). Hibernate adds the
-- column; every farmer and collector that existed before is considered validated.
UPDATE UserCustomer SET validationStatus = 'VALIDATED' WHERE validationStatus IS NULL;
