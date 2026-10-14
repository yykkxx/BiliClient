package com.RobinNotBad.BiliClient.util

import androidx.core.util.Consumer

class Result<T> private constructor(
    private val realResult: Any,
    private val isSuccess: Boolean
) {

    private constructor(value: T) : this(Success(value) as Any, true)

    private constructor(e: Exception) : this(Failure(e) as Any, false)

    fun isSuccess(): Boolean {
        return isSuccess
    }

    @Throws(Exception::class)
    fun getOrThrow(): T {
        if (isSuccess() && realResult is Success<*>) {
            val success = realResult as Success<T>
            if (success.value == null) {
                throw Exception("value is null")
            }
            return success.value as T
        }
        throw (realResult as Failure).error!!
    }

    fun getOrNull(): T? {
        if (isSuccess() && realResult is Success<*>) {
            return (realResult as Success<T>).value
        }
        return null
    }

    fun onSuccess(onSuccess: Consumer<T>): Result<T> {
        if (isSuccess()) {
            val value = getOrNull()
            if (value != null) {
                onSuccess.accept(value)
            }
        }
        return this
    }

    fun onFailure(onFailure: Consumer<Throwable>): Result<T> {
        if (!isSuccess() && realResult is Failure) {
            val error = (realResult as Failure).error
            if (error != null) {
                onFailure.accept(error as Throwable)
            }
        }
        return this
    }

    private class Success<T>(@JvmField var value: T?)

    private class Failure(@JvmField var error: Exception?)

    companion object {
        @JvmStatic
        fun <T> success(value: T?): Result<T> {
            return if (value != null) {
                Result(value)
            } else {
                Result<T>(NullPointerException())
            }
        }

        @JvmStatic
        fun <T> failure(error: Exception): Result<T> {
            return Result(error)
        }
    }
}
