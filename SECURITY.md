# Security policy

Security fixes target the default branch and newest released version. Older previews are not maintained separately.

Use **Security → Advisories → Report a vulnerability** when private vulnerability reporting is enabled. The maintainer should enable this before publishing. If unavailable, contact a maintainer through an explicitly published private contact method and request a secure channel. Do not post exploit details, credentials or personal data in public issues.

Include affected versions, the code path, safe reproduction steps and impact. Prefer synthetic library/media records. Maintainers should coordinate disclosure and document affected/fixed versions. Revoke/rotate any exposed credentials before cleaning history.

The public-source audit is preventative, not a complete security assessment. It scans the exported working-tree snapshot, not Git history, all possible secret formats or remote service behavior.
