-- Plots added to an already-existing farmer by the spreadsheet import never had a
-- synchronisation date, so the farmers export showed blank cells (client bug #11).
UPDATE Plot SET synchronisationDate = lastUpdated WHERE synchronisationDate IS NULL AND lastUpdated IS NOT NULL;
