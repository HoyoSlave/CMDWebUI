package com.cmdtweaks;

import android.content.pm.PackageInfo;
import rikka.parcelablelist.ParcelableListSlice;

interface IKsuWebuiStandaloneInterface {
    void destroy() = 16777114;
    ParcelableListSlice<PackageInfo> getPackages(int flags) = 1;
}
