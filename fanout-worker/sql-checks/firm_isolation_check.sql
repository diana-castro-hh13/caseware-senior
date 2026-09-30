\set ON_ERROR_STOP off
\set A '''aaaaaaaa-0000-0000-0000-000000000001'''
\set B '''bbbbbbbb-0000-0000-0000-000000000002'''

-- test data, loaded as the superuser
INSERT INTO template_version VALUES ('audit',4,'CA',3,'PUBLISHED',now()),('audit',5,'CA',4,'PUBLISHED',now()),
                                    ('audit',6,'CA',5,'WITHDRAWN',now()),('audit',7,'CA',5,'PUBLISHED',now());
INSERT INTO engagement_binding (engagement_id, firm_id, status, template_id, branch, current_version, declined_version) VALUES
 ('11111111-0000-0000-0000-000000000001', :A, 'VERIFIED','audit','CA',4,5),     -- declined v5, v7 should still be offered
 ('11111111-0000-0000-0000-000000000002', :A, 'VERIFIED','audit','CA',7,NULL),  -- already on the latest
 ('22222222-0000-0000-0000-000000000001', :B, 'VERIFIED','audit','CA',4,NULL),  -- other firm
 ('22222222-0000-0000-0000-000000000002', :B, 'UNKNOWN', NULL,NULL,NULL,NULL);

\echo '--- 1. API as firm A, query WITHOUT any firm filter: expect only firm A rows (2 rows)'
SET ROLE api_role; BEGIN; SET LOCAL app.firm_id = 'aaaaaaaa-0000-0000-0000-000000000001';
SELECT count(*) AS visible_rows FROM engagement_binding; COMMIT; RESET ROLE;

\echo '--- 2. API with NO firm set: expect 0 rows (fails closed)'
SET ROLE api_role; SELECT count(*) AS visible_rows FROM engagement_binding; RESET ROLE;

\echo '--- 3. API as firm A tries to read firm B by ID: expect 0 rows'
SET ROLE api_role; BEGIN; SET LOCAL app.firm_id = 'aaaaaaaa-0000-0000-0000-000000000001';
SELECT count(*) AS visible_rows FROM engagement_binding WHERE firm_id = 'bbbbbbbb-0000-0000-0000-000000000002'; COMMIT; RESET ROLE;

\echo '--- 4. API as firm A tries to change a firm B row: expect UPDATE 0'
SET ROLE api_role; BEGIN; SET LOCAL app.firm_id = 'aaaaaaaa-0000-0000-0000-000000000001';
UPDATE engagement_binding SET declined_version = 9 WHERE engagement_id = '22222222-0000-0000-0000-000000000001'; COMMIT; RESET ROLE;

\echo '--- 5. API as firm A tries to write a decision for firm B: expect an error'
SET ROLE api_role; BEGIN; SET LOCAL app.firm_id = 'aaaaaaaa-0000-0000-0000-000000000001';
INSERT INTO decision_audit (decision_id, engagement_id, firm_id, user_id, action, from_version, to_version, summary_id)
VALUES (gen_random_uuid(), '22222222-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000002', 'u1', 'DECLINE', 4, 7, 's1'); ROLLBACK; RESET ROLE;

\echo '--- 6. API as firm A records its own decision: expect INSERT 0 1'
SET ROLE api_role; BEGIN; SET LOCAL app.firm_id = 'aaaaaaaa-0000-0000-0000-000000000001';
INSERT INTO decision_audit (decision_id, engagement_id, firm_id, user_id, action, from_version, to_version, summary_id)
VALUES (gen_random_uuid(), '11111111-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000001', 'u1', 'DECLINE', 4, 7, 's1'); COMMIT; RESET ROLE;

\echo '--- 7. API tries to edit or delete the decision history: expect permission denied (twice)'
SET ROLE api_role; BEGIN; SET LOCAL app.firm_id = 'aaaaaaaa-0000-0000-0000-000000000001';
UPDATE decision_audit SET user_id = 'someone-else'; ROLLBACK;
SET ROLE api_role; BEGIN; SET LOCAL app.firm_id = 'aaaaaaaa-0000-0000-0000-000000000001';
DELETE FROM decision_audit; ROLLBACK; RESET ROLE;

\echo '--- 8. Checker sees all firms (4 rows) but cannot read decisions'
SET ROLE checker_role; SELECT count(*) AS visible_rows FROM engagement_binding;
SELECT count(*) FROM decision_audit; RESET ROLE;

\echo '--- 9. Screen query as firm A, no firm filter: expect only engagement ...0001 offered v7 (v6 is withdrawn, declined v5 does not hide v7)'
SET ROLE api_role; BEGIN; SET LOCAL app.firm_id = 'aaaaaaaa-0000-0000-0000-000000000001';
SELECT b.engagement_id, b.current_version, o.version AS offered_version
FROM engagement_binding b
JOIN LATERAL (SELECT v.version FROM template_version v
   WHERE v.template_id = b.template_id AND v.branch = b.branch AND v.status = 'PUBLISHED' AND v.version > b.current_version
   ORDER BY v.version DESC LIMIT 1) o ON true
WHERE b.status = 'VERIFIED' AND o.version > COALESCE(b.declined_version, 0); COMMIT; RESET ROLE;
