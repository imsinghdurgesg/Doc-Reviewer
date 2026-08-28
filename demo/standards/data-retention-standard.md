# Data Retention and Disposal Standard

Standard ID: GRC-DR-2026
Version: 1.0
Owner: Data Governance
Effective Date: 2026-01-01

## 1 Purpose and Scope

This standard defines the mandatory controls for retaining, archiving, and disposing of
organizational data. It applies to all systems that store customer, employee, or financial
records.

## 2 Definitions

"Retention period" is the minimum duration a record must be kept. "Disposal" is the
irreversible destruction of a record. A "data owner" is the individual accountable for a
data set.

## 3 Retention Periods

3.1 Financial records must be retained for a minimum of seven years.

3.2 Customer support records must be retained for a minimum of three years.

3.3 Retention periods must be documented in the data inventory for every system.

## 4 Archival

4.1 Records that have passed their active period must be moved to archival storage rather
than remaining in production systems.

4.2 Archived records must remain retrievable within five business days of a request.

## 5 Disposal

5.1 Records must be disposed of within ninety days of their retention period expiring.

5.2 Disposal must be irreversible. Logical deletion alone is not sufficient for records
containing personal data.

5.3 Every disposal action must be logged, including the record identifier, the disposal date,
and the person who authorised it.

## 6 Backups

6.1 Backup copies must be subject to the same retention periods as the primary records.

6.2 Backups containing personal data must be encrypted at rest.

## 7 Guidance

Teams should consider automating retention tagging where the platform supports it. This is a
recommendation and not a mandatory control.
