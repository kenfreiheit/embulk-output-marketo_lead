# Marketo Lead output plugin for Embulk(Beta)

This plugin is Beta Version.

embulk-output-marketo_lead is the gem preparing Embulk output plugins for Marketo Lead.

This plugin uses Marketo REST API.


## Overview

* **Plugin type**: output
* **Load all or nothing**: no
* **Resume supported**: no
* **Cleanup supported**: no

## Configuration

- **account_id**: Marketo AccountID (string, required)
- **client_id**: Marketo ClientID (string, required)
- **client_secret**: Marketo ClientSecret (string, required)
- **action**: API action (string, requierd)
- **lookupfield**: Lookup field (string, requierd)

## Example

```yaml
out:
  type: marketo_lead
  account_id: <Your Marketo AccountID>
  client_id: <Your Marketo ClientID>
  client_secret: <Your Marketo ClientSecret>
  action: createOrUpdate
  lookupfield: email
```


## Build

```
$ ./gradlew gem  # -t to watch change of files and rebuild continuously
```
