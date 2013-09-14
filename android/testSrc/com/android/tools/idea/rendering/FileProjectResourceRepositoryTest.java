/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.tools.idea.rendering;

import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.intellij.testFramework.PlatformTestUtil;
import junit.framework.TestCase;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class FileProjectResourceRepositoryTest extends TestCase {
  public void test() throws IOException {

    File dir = Files.createTempDir();
    assertNotNull(FileProjectResourceRepository.get(dir));
    // We shouldn't clear it out immediately on GC *eligibility*:
    System.gc();
    assertNotNull(FileProjectResourceRepository.getCached(dir));
    // However, in low memory conditions we should:
    PlatformTestUtil.tryGcSoftlyReachableObjects();
    System.gc();
    assertNull(FileProjectResourceRepository.getCached(dir));
  }

  public static void runOutOfMemory() {
    List<Object> objects = Lists.newArrayList();
    while (true) {
      try {
        objects.add(new String[1024*1024]);
      } catch (OutOfMemoryError error) {
        return;
      }
    }
  }
}
