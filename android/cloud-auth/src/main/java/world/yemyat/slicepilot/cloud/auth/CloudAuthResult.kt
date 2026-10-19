package world.yemyat.slicepilot.cloud.auth

sealed class CloudAuthResult<out T> {
    data class Ok<T>(val value: T) : CloudAuthResult<T>()
    data class Err(val message: String) : CloudAuthResult<Nothing>()
}
