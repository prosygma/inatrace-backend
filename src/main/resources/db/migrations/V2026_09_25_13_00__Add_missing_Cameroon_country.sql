-- import/countries.csv (source of the 2020 country prefill) had no Cameroon, so freshly provisioned
-- databases could not select it. Add it where it is missing; databases that already have it are untouched.
INSERT INTO Country (code, name)
SELECT 'CM', 'Cameroon' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM Country WHERE code = 'CM');
