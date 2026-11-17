/*
 * Copyright 2022 The Android Open Source Project
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
package my.torrstream.app.player.avi;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.ParsableByteArray;

/** Parses and contains the name from the STRN chunk. */
@OptIn(markerClass = UnstableApi.class)
/* package */ final class StreamNameChunk implements AviChunk {

  public static StreamNameChunk parseFrom(ParsableByteArray body) {
    String name = body.readString(body.bytesLeft());
    // Строка в strn оканчивается NUL (и байтом выравнивания) — в подпись дорожки он не нужен.
    int end = name.indexOf('\0');
    return new StreamNameChunk(end >= 0 ? name.substring(0, end) : name);
  }

  public final String name;

  private StreamNameChunk(String name) {
    this.name = name;
  }

  @Override
  public int getType() {
    return RobustAviExtractor.FOURCC_strn;
  }
}
