# Beautips acceptance fixtures v1

This bundle contains only invented, disposable acceptance data for the exact
managed Beautips WorkSession. It is not a production seed, backup or legacy
import.

The tenant and business user are created through the reviewed bootstrap APIs.
The operator supplies the two named synthetic administrator secret references
from the WorkSession boundary; no secret value belongs in this bundle or its
evidence. `database.sql` then inserts the remaining fixed records
idempotently. The asset and import file are copied byte-for-byte into the exact
session-owned volumes.

Every person, business, address, telephone number and `.invalid` address is
invented. WhatsApp, external delivery and production connectivity remain
disabled.
