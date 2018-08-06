/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.support.transition;

import android.graphics.drawable.Drawable;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.view.View;
import android.view.ViewOverlay;

@RequiresApi(18)
class ViewOverlayApi18 implements ViewOverlayImpl {

    private final ViewOverlay mViewOverlay;

    ViewOverlayApi18(@NonNull View view) {
        mViewOverlay = view.getOverlay();
    }

    @Override
    public void add(@NonNull Drawable drawable) {
        mViewOverlay.add(drawable);
    }

    @Override
    public void clear() {
        mViewOverlay.clear();
    }

    @Override
    public void remove(@NonNull Drawable drawable) {
        mViewOverlay.remove(drawable);
    }

}