/**
 * Copyright (C) 2014-2016 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.pinot.common.utils.retry;

import java.util.concurrent.Callable;


/**
 * Retry policy, encapsulating the logic needed to retry an operation until it succeeds.
 */
public interface RetryPolicy {
  /**
   * Attempts to do the operation until it succeeds, aborting if an exception is thrown by the operation.
   *
   * @param operation The operation to attempt, which returns true on success and false on failure.
   * @return true if the operation succeeded or false if the operation did not succeed within the retries specified by
   * this retry policy
   */
  boolean attempt(Callable<Boolean> operation);
}
