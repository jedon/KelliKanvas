# DLNA interoperability fixtures

The current QNAP and Twonky XML fixtures are synthetic, representative inputs. They exercise known UPnP structures but were not captured from physical hardware. Hardware interoperability with QNAP reindex behavior, vendor paging quirks, and firmware-specific description/SOAP variants therefore remains unverified.

## Importing a sanitized device fixture

1. Remove serial numbers, MAC addresses, credentials, public addresses, user media names, and other identifying data.
2. Replace the UDN with a stable synthetic value and replace LAN addresses with documentation-only private addresses.
3. Store the description XML under `src/test/resources/dlna/`.
4. Add a sibling `.fixture.properties` manifest:

```properties
formatVersion=1
provenance=sanitized-device-capture
vendor=QNAP
description=qnap-sanitized-description.xml
location=http://192.168.50.20:8200/root.xml
hardwareVerified=true
```

5. Add the manifest filename to `fixture-index.txt`. `DlnaFixtureImportFormatTest` validates provenance metadata and parses every indexed description.

Never label reconstructed or hand-authored XML as a device capture; use `provenance=synthetic-representative` and `hardwareVerified=false`.
