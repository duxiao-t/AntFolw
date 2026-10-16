package com.antflow.mobile.workflow;

import java.util.List;

public record MobileBootstrapDto(MobileUserDto user, int pendingCount, int draftCount,
                                  int unreadNotificationCount, List<MobileAppDto> favoriteApps,
                                  List<RecentProcessDto> recentProcesses, String brandingVersion) { }
