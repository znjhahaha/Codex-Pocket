package dev.codexpocket.app.data

enum class ModelCatalogState { Idle, Loading, Ready, Empty, Unsupported, Offline, RuntimeUnavailable, Failed }
enum class PairingPhase { Idle, Validating, Connecting, Authenticating, Synchronizing, Paired, Failed }
