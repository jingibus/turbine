/*
 * Copyright (C) 202 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.turbine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeout

/**
 * Returns the most recent item that has already been received.
 * If channel was closed with no item being received
 * previously, this function will throw an [AssertionError]. If channel
 * was closed with an exception, this function will throw the underlying exception.
 *
 * @throws AssertionError if no item was emitted.
 */
public fun <T> ReceiveChannel<T>.expectMostRecentItem(description: String? = null): T {
  var result: ChannelResult<T>? = null
  var prevResult: ChannelResult<T>?
  while (true) {
    prevResult = result
    result = tryReceive()
    result.exceptionOrNull()?.let { throw it }
    if (!result.isSuccess) {
      break
    }
  }

  if (prevResult?.isSuccess == true) return prevResult!!.getOrThrow()
  throw TurbineAssertionError("No item was found".prefix(description), cause = null)
}

/**
 * Assert that there are no unconsumed events which have already been received.
 *
 * A channel in the closed state will always emit either [Event.Complete] or [Event.Error] when read, so
 * [expectNoEvents] will only succeed on an empty [ReceiveChannel] that is not closed.
 *
 * @throws AssertionError if unconsumed events are found.
 */
public fun <T> ReceiveChannel<T>.expectNoEvents(description: String? = null) {
  val result = tryReceive()
  if (result.isSuccess || result.isClosed) result.unexpectedResult("no events", description)
}

/**
 * Assert that an event was received and return it.
 * This function will suspend if no events have been received.
 *
 * This function will always return a terminal event on a closed [ReceiveChannel].
 */
public suspend fun <T> ReceiveChannel<T>.awaitEvent(
  timeoutMillis: Long = 1000,
  description: String? = null,
): Event<T> = try {
  withTimeout(timeoutMillis) {
    try {
      Event.Item(receive())
    } catch (e: CancellationException) {
      throw e
    } catch (e: ClosedReceiveChannelException) {
      Event.Complete
    } catch (e: Exception) {
      Event.Error(e)
    }
  }
} catch (e: TimeoutCancellationException) {
error(description?.let { "No value produced for $it in ${timeoutMillis}ms" }
  ?: "No value produced in ${timeoutMillis}ms")
}

/**
 * Assert that the next event received was non-null and return it.
 * This function will not suspend, and will throw if invoked in a suspending context.
 *
 * @throws AssertionError if the next event was completion or an error.
 */
public fun <T> ReceiveChannel<T>.takeEvent(
  description: String? = null,
): Event<T> {
  assertCallingContextIsNotSuspended()
  return takeEventUnsafe()
    ?: unexpectedEvent(null, "an event", description)
}

internal fun <T> ReceiveChannel<T>.takeEventUnsafe(): Event<T>? {
  return tryReceive().toEvent()
}

/**
 * Assert that the next event received was an item and return it.
 * This function will not suspend, and will throw if invoked in a suspending context.
 *
 * @throws AssertionError if the next event was completion or an error, or no event.
 */
public fun <T> ReceiveChannel<T>.takeItem(description: String? = null): T {
  val event = takeEvent(description)
  return (event as? Event.Item)?.value ?: unexpectedEvent(event, "item", description)
}

/**
 * Assert that the next event received is [Event.Complete].
 * This function will not suspend, and will throw if invoked in a suspending context.
 *
 * @throws AssertionError if the next event was completion or an error.
 */
public fun <T> ReceiveChannel<T>.takeComplete(description: String? = null) {
  val event = takeEvent(description)
  if (event !is Event.Complete) unexpectedEvent(event, "complete", description)
}

/**
 * Assert that the next event received is [Event.Error], and return the error.
 * This function will not suspend, and will throw if invoked in a suspending context.
 *
 * @throws AssertionError if the next event was completion or an error.
 */
public fun <T> ReceiveChannel<T>.takeError(description: String? = null): Throwable {
  val event = takeEvent(description)
  return (event as? Event.Error)?.throwable ?: unexpectedEvent(event, "error", description)
}

/**
 * Assert that the next event received was an item and return it.
 * This function will suspend if no events have been received.
 *
 * @throws AssertionError if the next event was completion or an error.
 */
public suspend fun <T> ReceiveChannel<T>.awaitItem(
  timeoutMillis: Long = 1000,
  description: String? = null,
): T =
    when (val result = awaitEvent(timeoutMillis, description)) {
      is Event.Item -> result.value
      else -> unexpectedEvent(result, "item", description)
    }


/**
 * Assert that [count] item events were received and ignore them.
 * This function will suspend if no events have been received.
 *
 * @throws AssertionError if one of the events was completion or an error.
 */
public suspend fun <T> ReceiveChannel<T>.skipItems(
  count: Int,
  timeoutMillis: Long = 1000,
  description: String? = null,
) {
  repeat(count) { index ->
    when (val event = awaitEvent(timeoutMillis, description)) {
      Event.Complete, is Event.Error -> {
        val cause = (event as? Event.Error)?.throwable
        throw TurbineAssertionError("Expected $count items but got $index items and $event", cause)
      }
      is Event.Item<T> -> { /* Success */ }
    }
  }
}

/**
 * Assert that attempting to read from the [ReceiveChannel] yields [ClosedReceiveChannelException], indicating
 * that it was closed without an exception.
 *
 * @throws AssertionError if the next event was an item or an error.
 */
public suspend fun <T> ReceiveChannel<T>.awaitComplete(
  timeoutMillis: Long = 1000,
  description: String? = null,
) {
  val event = awaitEvent()
  if (event != Event.Complete) {
    unexpectedEvent(event, "complete", description)
  }
}

/**
 * Assert that attempting to read from the [ReceiveChannel] yields an exception, indicating
 * that it was closed with an exception.
 *
 * @throws AssertionError if the next event was an item or completion.
 */
public suspend fun  <T> ReceiveChannel<T>.awaitError(
  timeoutMillis: Long = 1000,
  description: String? = null,
): Throwable {
  val event = awaitEvent()
  return (event as? Event.Error)?.throwable
    ?: unexpectedEvent(event, "error", description)
}

internal fun <T> ChannelResult<T>.toEvent(): Event<T>? {
  val cause = exceptionOrNull()

  return if (isSuccess) Event.Item(getOrThrow())
  else if (cause != null) Event.Error(cause)
  else if (isClosed) Event.Complete
  else null
}
private fun <T> ChannelResult<T>.unexpectedResult(expected: String, description: String?): Nothing =
  unexpectedEvent(toEvent(), expected, description)

private fun unexpectedEvent(event: Event<*>?, expected: String, description: String?): Nothing {
  val cause = (event as? Event.Error)?.throwable
  val eventAsString = event?.toString() ?: "no items"
  throw TurbineAssertionError("Expected $expected but found $eventAsString".prefix(description), cause)
}

private fun String.prefix(description: String?) =
  if (description != null) {
    "$description: $this"
  } else {
    this
  }
