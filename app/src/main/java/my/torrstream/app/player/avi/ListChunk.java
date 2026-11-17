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
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import com.google.common.collect.ImmutableList;

/** Represents an AVI LIST. */
@OptIn(markerClass = UnstableApi.class)
/* package */ final class ListChunk implements AviChunk {

  /**
   * Отличия от Media3: тело элемента RIFF дополняется до чётной длины (у Media3 после «strn»
   * нечётной длины следующий заголовок читался со сдвигом на байт, мусорный размер уводил
   * {@code setLimit} за конец hdrl — IllegalArgumentException на старте); элемент, заявивший
   * размер больше остатка списка, обрезается по списку; испорченный элемент пропускается.
   */
  public static ListChunk parseFrom(int listType, ParsableByteArray body) {
    ImmutableList.Builder<AviChunk> builder = new ImmutableList.Builder<>();
    int listBodyEndPosition = body.limit();
    @C.TrackType int currentTrackType = C.TRACK_TYPE_NONE;
    while (body.bytesLeft() >= 8) {
      int type = body.readLittleEndianInt();
      long declaredSize = body.readLittleEndianUnsignedInt();
      int start = body.getPosition();
      int size = (int) Math.min(declaredSize, listBodyEndPosition - start);
      int innerBoxBodyEndPosition = start + size;
      body.setLimit(innerBoxBodyEndPosition);
      @Nullable AviChunk aviChunk = null;
      try {
        if (type == RobustAviExtractor.FOURCC_LIST) {
          if (size >= 4) {
            int innerListType = body.readLittleEndianInt();
            aviChunk = parseFrom(innerListType, body);
          }
        } else {
          aviChunk = createBox(type, currentTrackType, body);
        }
      } catch (RuntimeException e) {
        // Короткий или битый элемент заголовка: без него, как без незнакомого.
        Log.w(TAG, "Skipping malformed chunk " + Integer.toHexString(type), e);
        aviChunk = null;
      }
      if (aviChunk != null) {
        if (aviChunk.getType() == RobustAviExtractor.FOURCC_strh) {
          currentTrackType = ((AviStreamHeaderChunk) aviChunk).getTrackType();
        }
        builder.add(aviChunk);
      }
      body.setLimit(listBodyEndPosition);
      // Выравнивание RIFF: за телом нечётной длины — байт-заполнитель.
      body.setPosition(Math.min(innerBoxBodyEndPosition + (size & 1), listBodyEndPosition));
    }
    return new ListChunk(listType, builder.build());
  }

  private static final String TAG = "ListChunk";

  public final ImmutableList<AviChunk> children;
  private final int type;

  private ListChunk(int type, ImmutableList<AviChunk> children) {
    this.type = type;
    this.children = children;
  }

  @Override
  public int getType() {
    return type;
  }

  @Nullable
  @SuppressWarnings("unchecked")
  public <T extends AviChunk> T getChild(Class<T> c) {
    for (AviChunk aviChunk : children) {
      if (aviChunk.getClass() == c) {
        return (T) aviChunk;
      }
    }
    return null;
  }

  @Nullable
  private static AviChunk createBox(
      int chunkType, @C.TrackType int trackType, ParsableByteArray body) {
    switch (chunkType) {
      case RobustAviExtractor.FOURCC_avih:
        return AviMainHeaderChunk.parseFrom(body);
      case RobustAviExtractor.FOURCC_strh:
        return AviStreamHeaderChunk.parseFrom(body);
      case RobustAviExtractor.FOURCC_strf:
        return StreamFormatChunk.parseFrom(trackType, body);
      case RobustAviExtractor.FOURCC_strn:
        return StreamNameChunk.parseFrom(body);
      default:
        return null;
    }
  }
}
