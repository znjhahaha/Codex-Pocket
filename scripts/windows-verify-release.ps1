#requires -Version 5.1
[CmdletBinding()]
param([string]$ReleaseDirectory = (Join-Path $PSScriptRoot '..'), [switch]$WindowsOnly)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'windows-common.ps1')
if ($WindowsOnly) { $manifest = Test-PocketWindowsDirectory $ReleaseDirectory }
else { $manifest = Test-PocketReleaseDirectory $ReleaseDirectory }
[pscustomobject]@{version=$manifest.version;verifiedFiles=$manifest.files.Count;hashAlgorithm='SHA256';integrityVerified=$true}
# Hashes detect changed/incomplete files. They do not authenticate the publisher.
