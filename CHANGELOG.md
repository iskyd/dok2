# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-08-12

### Added
- Initial project structure with three-module architecture (`:domain`, `:data`, `:app`)
- FilterChain with anchor-based displacement logic
- HgtReader for DEM elevation lookup
- GpxWriter for GPX 1.1 export
- Room database schema for tracks, trackpoints, waypoints, and pause events
- MapLibre integration with PMTiles support
- GitHub Actions workflow for automated release builds
- Obtainium-compatible release distribution

### Notes
- Pre-alpha release. Core recording functionality is under development.
- The app has no network permission by design.
