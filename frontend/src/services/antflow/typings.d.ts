declare namespace AntFlowAPI {
  type AccessInfo = {
    mobileHomeUrl?: string;
    oauthCallbackUrl?: string;
    publicBaseUrl?: string;
    secure?: boolean;
    trustedDomain?: string;
    verificationFileUrl?: string;
  };

  type AccountProfile = {
    departmentId?: number;
    departmentName?: string;
    displayName?: string;
    email?: string;
    employeeNo?: string;
    id?: number;
    phone?: string;
    position?: string;
    roles?: string[];
    username?: string;
  };

  type addAssigneeParams = {
    id: number;
  };

  type adminPageParams = {
    page?: number;
    size?: number;
    formDefId?: number;
    status?: string;
    createdBy?: number;
    submitterKeyword?: string;
  };

  type AdminTerminateRequest = {
    reason?: string;
    ticketNo?: string;
  };

  type ApprovalCommentPresets = {
    approve?: string[];
    reject?: string[];
  };

  type ApprovalPreviewAssigneeDto = {
    displayName?: string;
    userId?: number;
  };

  type ApprovalPreviewDto = {
    nodes?: ApprovalPreviewNodeDto[];
  };

  type ApprovalPreviewNodeDto = {
    approvalMode?: string;
    assignees?: ApprovalPreviewAssigneeDto[];
    autoPass?: boolean;
    deferred?: boolean;
    nodeId?: string;
    nodeName?: string;
  };

  type approvalPreviewParams = {
    code: string;
  };

  type ApprovalPreviewRequest = {
    data?: JsonNode;
    draftId?: number;
    reworkTaskId?: number;
    selfSelected?: Record<string, any>;
  };

  type ApprovalRecordDto = {
    branchId?: string;
    comment?: string;
    completedAt?: string;
    department?: string;
    employeeNo?: string;
    id?: string;
    nodeId?: string;
    nodeName?: string;
    nodeType?: string;
    operationKind?: string;
    operatorName?: string;
    parallelId?: string;
    receivedAt?: string;
    recordKind?: string;
    roundNo?: number;
    sourceOperatorName?: string;
    status?: string;
    taskId?: number;
  };

  type ApprovalSummary = {
    byDay?: DayRow[];
    byDepartment?: DeptRow[];
    byForm?: FormRow[];
    totals?: ReportTotals;
  };

  type ApprovalSummaryDto = {
    complete?: boolean;
    completedCount?: number;
    flowedCount?: number;
    processingCount?: number;
  };

  type approvalSummaryParams = {
    from?: string;
    to?: string;
    formDefIds?: number[];
    deptIds?: number[];
    tzOffsetMinutes?: number;
  };

  type approve1Params = {
    id: number;
  };

  type approveParams = {
    id: number;
  };

  type appsParams = {
    keyword?: string;
    category?: string;
  };

  type ArchiveDto = {
    createdAt?: string;
    errorMessage?: string;
    eventCount?: number;
    id?: string;
    keyId?: string;
    objectKey?: string;
    rangeEnd?: string;
    rangeStart?: string;
    sha256?: string;
    status?: string;
    verifiedAt?: string;
  };

  type AuditEventDto = {
    action?: string;
    actorDisplayName?: string;
    actorUserId?: number;
    actorUsername?: string;
    clientIp?: string;
    failureCode?: string;
    fieldDiff?: Record<string, any>;
    id?: number;
    metadata?: Record<string, any>;
    occurredAt?: string;
    requestId?: string;
    resourceId?: string;
    resourceType?: string;
    result?: string;
    riskLevel?: string;
    sessionId?: string;
    userAgent?: string;
  };

  type AuditPage = {
    page?: number;
    records?: AuditEventDto[];
    size?: number;
    total?: number;
  };

  type authorize1Params = {
    code: string;
    returnUrl?: string;
  };

  type authorizeParams = {
    returnUrl?: string;
  };

  type BackupFile = {
    bytes?: number;
    createdAt?: string;
    name?: string;
  };

  type bindableParams = {
    formId: number;
  };

  type BindableSource = {
    code?: string;
    columns?: string[];
    id?: number;
    latestVersionNo?: number;
    name?: string;
    rowCount?: number;
    versionId?: number;
    versionNo?: number;
  };

  type BindingDto = {
    displayName?: string;
    id?: number;
    lastLoginAt?: string;
    subject?: string;
    userId?: number;
    username?: string;
  };

  type bindingsParams = {
    id: number;
  };

  type byCodeParams = {
    code: string;
  };

  type byFormParams = {
    formDefId: number;
  };

  type callback1Params = {
    provider: string;
    state: string;
    code: string;
  };

  type callbackParams = {
    state: string;
    code: string;
  };

  type candidatesParams = {
    formId: number;
  };

  type childrenParams = {
    id: number;
  };

  type commentPresetsParams = {
    id: number;
  };

  type Company = {
    createdAt?: string;
    id?: number;
    name?: string;
  };

  type CompanyRequest = {
    companyId?: number;
  };

  type contentParams = {
    id: string;
  };

  type CreateCompanyRequest = {
    name?: string;
  };

  type CreateRequest = {
    agentId?: number;
    endsAt?: string;
    formDefinitionId?: number;
    startsAt?: string;
  };

  type DayRow = {
    date?: string;
    finished?: number;
    started?: number;
  };

  type delegateParams = {
    id: number;
  };

  type Delegation = {
    agentId?: number;
    agentName?: string;
    endsAt?: string;
    formDefinitionId?: number;
    id?: number;
    startsAt?: string;
    status?: string;
  };

  type delete1Params = {
    name: string;
  };

  type delete2Params = {
    id: number;
    version: number;
  };

  type delete3Params = {
    id: number;
  };

  type delete4Params = {
    id: number;
  };

  type delete5Params = {
    id: string;
  };

  type delete6Params = {
    id: number;
  };

  type delete7Params = {
    id: number;
  };

  type deleteByFormParams = {
    formDefId: number;
  };

  type deleteDraftParams = {
    id: number;
  };

  type deleteUsingDELETEParams = {
    id: number;
  };

  type DeliveryStatus = {
    dead?: number;
    oldestPendingAt?: string;
    pending?: number;
  };

  type Department = {
    canManageDepartment?: boolean;
    canManageUsers?: boolean;
    canReadMembers?: boolean;
    companyId?: number;
    contextOnly?: boolean;
    createdAt?: string;
    id?: number;
    leaderId?: number;
    leaderIds?: number[];
    name?: string;
    parentId?: number;
    path?: string;
    sortOrder?: number;
  };

  type DepartmentCandidate = {
    id?: number;
    name?: string;
  };

  type departmentParams = {
    id: number;
  };

  type departments1Params = {
    keyword?: string;
  };

  type departmentsParams = {
    companyId?: number;
  };

  type DeptRow = {
    approvalRate?: number;
    approved?: number;
    avgDurationHours?: number;
    deptId?: number;
    deptName?: string;
    other?: number;
    rejected?: number;
    running?: number;
    started?: number;
    withdrawn?: number;
  };

  type detail1Params = {
    id: number;
  };

  type detail2Params = {
    id: number;
  };

  type detailParams = {
    id: number;
  };

  type DeviceSessionDto = {
    deviceName?: string;
    id?: string;
    isCurrent?: boolean;
    lastActiveAt?: string;
    platform?: string;
  };

  type diffParams = {
    id: number;
    versionId: number;
  };

  type DirectSubmitRequest = {
    companyId?: number;
    data?: Record<string, any>;
    draftId?: number;
    files?: MobileFileRef[];
    formCode?: string;
    status?: string;
  };

  type disable1Params = {
    id: number;
  };

  type disable2Params = {
    id: number;
  };

  type disableParams = {
    id: number;
  };

  type disableVersionParams = {
    id: number;
    versionId: number;
  };

  type discardVersionParams = {
    id: number;
    versionId: number;
  };

  type download1Params = {
    id: string;
  };

  type downloadParams = {
    name: string;
  };

  type draftByFormParams = {
    formDefId: number;
  };

  type draftParams = {
    id: number;
  };

  type effectiveParams = {
    id: number;
  };

  type EffectivePermissionDto = {
    admin?: boolean;
    departmentId?: number;
    permissionScopes?: Record<string, any>;
    permissions?: string[];
    roleCodes?: string[];
    userId?: number;
  };

  type enableParams = {
    id: number;
  };

  type enableVersionParams = {
    id: number;
    versionId: number;
  };

  type events2Params = {
    from?: string;
    to?: string;
    operatorId?: number;
    action?: string;
    resourceType?: string;
    resourceId?: string;
    result?: string;
    riskLevel?: string;
    ip?: string;
    page?: number;
    size?: number;
  };

  type export1Params = {
    formDefId?: number;
    status?: string;
    submitterKeyword?: string;
    from?: string;
    to?: string;
    tzOffsetMinutes?: number;
    format?: string;
  };

  type export2Params = {
    from?: string;
    to?: string;
    operatorId?: number;
    action?: string;
    resourceType?: string;
    resourceId?: string;
    result?: string;
    riskLevel?: string;
    ip?: string;
  };

  type exportUsingGETParams = {
    keyword?: string;
    deptId?: number;
    includeDescendants?: boolean;
    leaderOnly?: boolean;
    position?: string;
    userIds?: number[];
    scopeType?: string;
    format?: string;
    companyId?: number;
  };

  type FavoriteAppsRequest = {
    formIds?: number[];
  };

  type FieldValue = {
    detailText?: string;
    displayText?: string;
    fieldId?: string;
    fieldName?: string;
    value?: Record<string, any>;
  };

  type FormData = {
    businessNo?: string;
    companyId?: number;
    companyInferred?: boolean;
    createdAt?: string;
    createdBy?: number;
    createdByDeptName?: string;
    createdByEmployeeNo?: string;
    createdByName?: string;
    currentRevisionId?: number;
    data?: string;
    fieldValues?: FieldValue[];
    formDefId?: number;
    formDefVersion?: number;
    id?: number;
    status?: string;
    updatedAt?: string;
  };

  type formDataCountParams = {
    formDefId?: number;
    status?: string;
    submitterKeyword?: string;
    from?: string;
    to?: string;
    tzOffsetMinutes?: number;
  };

  type FormDefinition = {
    authzVersion?: number;
    code?: string;
    createdAt?: string;
    createdBy?: number;
    deleted?: number;
    description?: string;
    id?: number;
    name?: string;
    schema?: string;
    settings?: string;
    status?: string;
    updatedAt?: string;
    version?: number;
  };

  type FormGrantCandidates = {
    companies?: GrantCompany[];
    departments?: GrantDepartment[];
    roles?: GrantRole[];
  };

  type FormGrantDto = {
    companies?: GrantCompany[];
    companyIds?: number[];
    departmentIds?: number[];
    departments?: GrantDepartment[];
    roleIds?: number[];
    roles?: GrantRole[];
    userIds?: number[];
    users?: GrantUser[];
    version?: number;
  };

  type FormGrantWriteRequest = {
    companyIds?: number[];
    departmentIds?: number[];
    roleIds?: number[];
    userIds?: number[];
    version?: number;
  };

  type FormMaintainerDto = {
    userIds?: number[];
    users?: GrantUser[];
    version?: number;
  };

  type FormMaintainerWriteRequest = {
    userIds?: number[];
    version?: number;
  };

  type formParams = {
    code: string;
    draftId?: number;
    reworkTaskId?: number;
  };

  type FormRef = {
    code?: string;
    id?: number;
    name?: string;
  };

  type FormRefWrite = {
    formIds?: number[];
    version?: number;
  };

  type FormRow = {
    approvalRate?: number;
    approved?: number;
    avgDurationHours?: number;
    formDefId?: number;
    formName?: string;
    other?: number;
    rejected?: number;
    running?: number;
    started?: number;
    withdrawn?: number;
  };

  type formsParams = {
    id: number;
  };

  type get1Params = {
    formId: number;
  };

  type get2Params = {
    id: number;
  };

  type get3Params = {
    id: number;
  };

  type getParams = {
    id: number;
  };

  type GrantCompany = {
    id?: number;
    name?: string;
  };

  type GrantDepartment = {
    id?: number;
    name?: string;
    parentId?: number;
  };

  type GrantRole = {
    code?: string;
    id?: number;
    name?: string;
  };

  type grantsParams = {
    id: number;
  };

  type GrantUser = {
    departmentId?: number;
    departmentName?: string;
    displayName?: string;
    employeeNo?: string;
    id?: number;
    username?: string;
  };

  type GrantUserPage = {
    items?: GrantUser[];
    page?: number;
    size?: number;
    total?: number;
  };

  type GrantWrite = {
    roleIds?: number[];
    userIds?: number[];
    version?: number;
  };

  type historyParams = {
    id: number;
  };

  type importDraftParams = {
    id: number;
    text?: string;
    sheetName?: string;
    note?: string;
  };

  type ImportFailure = {
    message?: string;
    row?: number;
  };

  type ImportPreview = {
    columns?: string[];
    rowCount?: number;
    rows?: Record<string, any>[];
    sheets?: string[];
  };

  type ImportRequest = {
    users?: Record<string, any>[];
  };

  type ImportResult = {
    failedCount?: number;
    failures?: ImportFailure[];
    passwordResetRequired?: boolean;
    successCount?: number;
  };

  type initiatedParams = {
    page?: number;
    size?: number;
    keyword?: string;
    status?: string;
  };

  type inspectParams = {
    text?: string;
    sheetName?: string;
  };

  type instanceParams = {
    id: number;
  };

  type instancesParams = {
    page?: number;
    size?: number;
    keyword?: string;
    status?: string;
  };

  type JobDto = {
    companyId?: number;
    createdUsers?: number;
    errorSummary?: string[];
    failedUsers?: number;
    finishedAt?: string;
    id?: number;
    message?: string;
    percent?: number;
    phase?: string;
    processedUsers?: number;
    startedAt?: string;
    status?: string;
    syncMode?: string;
    totalUsers?: number;
    updatedUsers?: number;
  };

  type jobParams = {
    id: number;
  };

  type JsonNode = true;

  type JsSdkConfig = {
    appId?: string;
    nonceStr?: string;
    signature?: string;
    timestamp?: number;
  };

  type jsSdkConfigParams = {
    url: string;
  };

  type list4Params = {
    scope?: string;
    page?: number;
    size?: number;
    status?: string;
    startedBy?: number;
    keyword?: string;
    from?: string;
    to?: string;
  };

  type list5Params = {
    page?: number;
    size?: number;
    keyword?: string;
    status?: string;
  };

  type listParams = {
    keyword?: string;
    deptId?: number;
    includeDescendants?: boolean;
    leaderOnly?: boolean;
    position?: string;
    userIds?: number[];
    scopeType?: string;
    companyId?: number;
  };

  type LocalTime = {
    hour?: number;
    minute?: number;
    nano?: number;
    second?: number;
  };

  type LoginAccessRequest = {
    enabled: boolean;
  };

  type loginParams = {
    includeMobileBootstrap?: boolean;
  };

  type LoginReq = {
    password?: string;
    username?: string;
  };

  type maintainersParams = {
    formId: number;
  };

  type ManagerCandidate = {
    deptId?: number;
    displayName?: string;
    employeeNo?: string;
    id?: number;
  };

  type managerCandidatesParams = {
    deptId: number;
    excludeUserId?: number;
    keyword?: string;
  };

  type markReadParams = {
    id: number;
  };

  type markTaskReadParams = {
    id: number;
  };

  type MediaRequest = {
    mediaType?: string;
    serverId?: string;
  };

  type MenuDocument = {
    nodes?: MenuNode[];
    version?: number;
  };

  type MenuNode = {
    icon?: string;
    name?: string;
    nodeKey?: string;
    pageKey?: string;
    parentKey?: string;
    requiredPermissions?: string[];
    sortOrder?: number;
    type?: string;
    visible?: boolean;
  };

  type messageStatusParams = {
    companyId: number;
  };

  type metadataParams = {
    id: string;
  };

  type MobileAppDto = {
    category?: string;
    categoryLabel?: string;
    code?: string;
    description?: string;
    formId?: number;
    iconUrl?: string;
    name?: string;
  };

  type MobileBootstrapDto = {
    brandingVersion?: string;
    draftCount?: number;
    favoriteApps?: MobileAppDto[];
    pendingCount?: number;
    recentProcesses?: RecentProcessDto[];
    unreadNotificationCount?: number;
    user?: MobileUserDto;
  };

  type MobileDirectSubmissionDetailDto = {
    businessNo?: string;
    files?: MobileFileDto[];
    formCode?: string;
    formData?: JsonNode;
    formName?: string;
    id?: number;
    schema?: JsonNode;
    status?: string;
    submittedAt?: string;
  };

  type MobileDraftDto = {
    companyId?: number;
    companyInferred?: boolean;
    createdAt?: string;
    data?: JsonNode;
    formCode?: string;
    formDefId?: number;
    formName?: string;
    formVersion?: number;
    id?: number;
    readOnly?: boolean;
    schema?: JsonNode;
    updatedAt?: string;
  };

  type MobileDraftRequest = {
    companyId?: number;
    data?: JsonNode;
    formCode?: string;
  };

  type MobileFileDto = {
    contentType?: string;
    contentUrl?: string;
    durationSeconds?: number;
    failureReason?: string;
    id?: string;
    name?: string;
    size?: number;
    status?: string;
  };

  type MobileFileRef = {
    fieldId?: string;
    fileId?: string;
    sortOrder?: number;
  };

  type MobileFormDto = {
    code?: string;
    name?: string;
    process?: JsonNode;
    schema?: JsonNode;
    settings?: JsonNode;
    starterFieldModes?: Record<string, any>;
    version?: number;
  };

  type MobileHistoryDto = {
    action?: string;
    comment?: string;
    createdAt?: string;
    fromNodeId?: string;
    id?: number;
    operatorId?: number;
    taskId?: number;
    toNodeId?: string;
  };

  type MobileInitiatedDto = {
    businessNo?: string;
    currentNodeName?: string;
    finishedAt?: string;
    formName?: string;
    id?: number;
    kind?: string;
    startedAt?: string;
    status?: string;
  };

  type MobileInstanceDetailDto = {
    applicantDepartment?: string;
    applicantEmployeeNo?: string;
    applicantName?: string;
    approvalRecords?: ApprovalRecordDto[];
    approvalSummary?: ApprovalSummaryDto;
    businessNo?: string;
    canWithdraw?: boolean;
    currentNodeName?: string;
    files?: MobileFileDto[];
    formData?: JsonNode;
    formName?: string;
    history?: MobileHistoryDto[];
    id?: number;
    processSnapshot?: JsonNode;
    schema?: JsonNode;
    startedAt?: string;
    status?: string;
    visibility?: string;
  };

  type MobileInstanceDto = {
    businessNo?: string;
    currentNodeName?: string;
    finishedAt?: string;
    formName?: string;
    id?: number;
    startedAt?: string;
    status?: string;
  };

  type MobilePageDtoMobileInitiatedDto = {
    hasMore?: boolean;
    items?: MobileInitiatedDto[];
  };

  type MobilePageDtoMobileInstanceDto = {
    hasMore?: boolean;
    items?: MobileInstanceDto[];
  };

  type MobilePageDtoMobileTaskDto = {
    hasMore?: boolean;
    items?: MobileTaskDto[];
  };

  type MobilePickerDepartmentDto = {
    id?: number;
    name?: string;
  };

  type MobilePickerUserDto = {
    department?: string;
    displayName?: string;
    employeeNo?: string;
    id?: number;
    username?: string;
  };

  type MobileStartResult = {
    businessNo?: string;
    firstTaskIds?: number[];
    formDataId?: number;
    instanceId?: number;
  };

  type MobileTaskActionRequest = {
    comment?: string;
    data?: JsonNode;
    rejectToNodeId?: string;
  };

  type MobileTaskDetailDto = {
    allowedActions?: string[];
    approvalRecords?: ApprovalRecordDto[];
    approvalSummary?: ApprovalSummaryDto;
    commentPresets?: ApprovalCommentPresets;
    files?: MobileFileDto[];
    formData?: JsonNode;
    history?: MobileHistoryDto[];
    processSnapshot?: JsonNode;
    rejectDisabled?: boolean;
    rejectTargets?: RejectTargetDto[];
    schema?: JsonNode;
    task?: MobileTaskDto;
  };

  type MobileTaskDto = {
    applicantDepartment?: string;
    applicantEmployeeNo?: string;
    applicantName?: string;
    businessNo?: string;
    createdAt?: string;
    formCode?: string;
    formName?: string;
    id?: number;
    instanceId?: number;
    instanceStatus?: string;
    nodeId?: string;
    nodeName?: string;
    readAt?: string;
    taskStatus?: string;
    taskType?: string;
  };

  type MobileUserDto = {
    department?: string;
    deptId?: number;
    displayName?: string;
    employeeNo?: string;
    id?: number;
    roles?: string[];
    username?: string;
  };

  type moveOrderParams = {
    id: number;
  };

  type movePositionParams = {
    id: number;
  };

  type myInboxParams = {
    view?: string;
    status?: string;
    page?: number;
    size?: number;
  };

  type mySubmissionsParams = {
    formCode?: string;
    page?: number;
    size?: number;
  };

  type NavNode = {
    children?: NavNode[];
    icon?: string;
    name?: string;
    pageKey?: string;
    requiredPermissions?: string[];
    sortOrder?: number;
    type?: string;
  };

  type NotificationPage = {
    hasMore?: boolean;
    items?: NotificationRow[];
    unreadCount?: number;
  };

  type NotificationRow = {
    createdAt?: string;
    eventType?: string;
    id?: number;
    instanceId?: number;
    readAt?: string;
    taskId?: number;
    title?: string;
  };

  type notificationsParams = {
    page?: number;
    pageSize?: number;
    unreadOnly?: boolean;
  };

  type OptionPage = {
    items?: OptionValue[];
    level?: number;
    page?: number;
    size?: number;
    stage?: string;
    total?: number;
    totalLevels?: number;
  };

  type OptionQuery = {
    dataId?: number;
    fieldId?: string;
    formCode?: string;
    formVersion?: number;
    instanceId?: number;
    keyword?: string;
    page?: number;
    path?: string[];
    selectedValues?: string[];
    size?: number;
    values?: Record<string, any>;
  };

  type OptionValue = {
    label?: string;
    value?: string;
  };

  type OrderItem = {
    asc?: boolean;
    column?: string;
  };

  type OutboxEvent = {
    attempts?: number;
    createdAt?: string;
    deliveredAt?: string;
    error?: string;
    eventType?: string;
    id?: string;
    instanceId?: number;
    nextAttemptAt?: string;
    status?: string;
  };

  type OutboxPage = {
    page?: number;
    records?: OutboxEvent[];
    size?: number;
    total?: number;
  };

  type outboxParams = {
    status?: string;
    page?: number;
    size?: number;
  };

  type overrideParams = {
    id: number;
  };

  type OverrideRequest = {
    action?: "APPROVE" | "REJECT";
    reason?: string;
    rejectToNodeId?: string;
    ticketNo?: string;
  };

  type Overview = {
    completedToday?: number;
    pendingTaskItems?: PendingTaskItem[];
    pendingTasks?: number;
    recentInstanceItems?: RecentInstanceItem[];
    rejectedToday?: number;
    runningInstances?: number;
    statusBreakdown?: Record<string, any>;
  };

  type overview1Params = {
    limit?: number;
  };

  type overviewParams = {
    pendingPage?: number;
    pendingSize?: number;
    recentPage?: number;
    recentSize?: number;
  };

  type PageFormData = {
    countId?: string;
    current?: number;
    maxLimit?: number;
    optimizeCountSql?: PageFormData;
    optimizeJoinOfCountSql?: boolean;
    orders?: OrderItem[];
    pages?: number;
    records?: FormData[];
    searchCount?: PageFormData;
    size?: number;
    total?: number;
  };

  type pageParams = {
    page?: number;
    size?: number;
    keyword?: string;
    deptId?: number;
    includeDescendants?: boolean;
    companyId?: number;
  };

  type PageSummary = {
    countId?: string;
    current?: number;
    maxLimit?: number;
    optimizeCountSql?: PageSummary;
    optimizeJoinOfCountSql?: boolean;
    orders?: OrderItem[];
    pages?: number;
    records?: Summary[];
    searchCount?: PageSummary;
    size?: number;
    total?: number;
  };

  type PageUser = {
    countId?: string;
    current?: number;
    maxLimit?: number;
    optimizeCountSql?: PageUser;
    optimizeJoinOfCountSql?: boolean;
    orders?: OrderItem[];
    pages?: number;
    records?: User[];
    searchCount?: PageUser;
    size?: number;
    total?: number;
  };

  type PasswordChange = {
    currentPassword?: string;
    newPassword?: string;
  };

  type pathParams = {
    id: number;
  };

  type PendingTaskItem = {
    applicantId?: number;
    applicantName?: string;
    createdAt?: string;
    formName?: string;
    instanceId?: number;
    nodeId?: string;
    nodeName?: string;
    parallelId?: string;
    procInstId?: number;
    status?: string;
    taskId?: number;
  };

  type PermissionDto = {
    adminOnly?: boolean;
    code?: string;
    defaultScope?: string;
    domain?: string;
    domainLabel?: string;
    name?: string;
    riskLevel?: string;
    scopeable?: boolean;
    sortOrder?: number;
  };

  type PermissionGrantDto = {
    code?: string;
    departmentIds?: number[];
    scopeOverride?: string;
  };

  type PermissionGrantWriteRequest = {
    code?: string;
    departmentIds?: number[];
    scopeOverride?: string;
  };

  type PermissionScopeDto = {
    all?: boolean;
    departmentIds?: number[];
    modes?: (
      | "SELF"
      | "DEPARTMENT"
      | "DEPARTMENT_AND_DESCENDANTS"
      | "CUSTOM"
      | "ALL"
    )[];
  };

  type PickerDepartment = {
    companyId?: number;
    id?: number;
    name?: string;
    parentId?: number;
  };

  type PickerRoleDto = {
    code?: string;
    id?: number;
    name?: string;
  };

  type previewParams = {
    formId: number;
  };

  type PreviewQuery = {
    query?: OptionQuery;
    schema?: JsonNode;
  };

  type ProcessDefinition = {
    createdAt?: string;
    createdBy?: number;
    formDefId?: number;
    id?: number;
    process?: string;
    status?: string;
    version?: number;
  };

  type ProcessInstance = {
    applicantDepartment?: string;
    applicantEmployeeNo?: string;
    applicantName?: string;
    businessNo?: string;
    companyId?: number;
    companyInferred?: boolean;
    currentFormRevisionId?: number;
    currentNodeId?: string;
    currentNodeInstanceId?: number;
    engineVersion?: number;
    finishedAt?: string;
    formCode?: string;
    formDataId?: number;
    formName?: string;
    id?: number;
    procDefId?: number;
    processDefVersion?: number;
    processDefinitionVersionId?: number;
    processSnapshot?: string;
    roundNo?: number;
    startedAt?: string;
    startedBy?: number;
    startedDeptId?: number;
    status?: string;
    version?: number;
  };

  type ProviderDto = {
    clientAuthMethod?: string;
    clientId?: string;
    code?: string;
    displayName?: string;
    enabled?: boolean;
    id?: number;
    issuerUri?: string;
    matchClaim?: string;
    matchField?: string;
    scopes?: string;
    secretConfigured?: boolean;
  };

  type PublicProvider = {
    code?: string;
    displayName?: string;
  };

  type publish1Params = {
    id: number;
    versionId: number;
  };

  type publish2Params = {
    id: number;
  };

  type publishParams = {
    id: number;
  };

  type PublishResult = {
    formDefinition?: FormDefinition;
    processDefinition?: ProcessDefinition;
  };

  type PublishWithProcessBody = {
    processDefinitionId?: number;
  };

  type publishWithProcessParams = {
    id: number;
  };

  type reassignParams = {
    id: number;
  };

  type ReassignRequest = {
    reason?: string;
    targetUserId?: number;
    ticketNo?: string;
  };

  type recallApprovalParams = {
    id: number;
  };

  type recallChildParams = {
    id: number;
  };

  type RecentInstanceItem = {
    currentNodeId?: string;
    currentNodeName?: string;
    formName?: string;
    instanceId?: number;
    startedAt?: string;
    startedById?: number;
    startedByName?: string;
    status?: string;
    updatedAt?: string;
  };

  type RecentProcessDto = {
    formCode?: string;
    formTitle?: string;
    instanceId?: number;
    status?: string;
    updatedAt?: string;
  };

  type refreshParams = {
    includeMobileBootstrap?: boolean;
  };

  type reject1Params = {
    id: number;
  };

  type rejectParams = {
    id: number;
  };

  type RejectTargetDto = {
    name?: string;
    nodeId?: string;
  };

  type replace1Params = {
    formId: number;
  };

  type replaceMaintainersParams = {
    formId: number;
  };

  type ReportTotals = {
    approvalRate?: number;
    approved?: number;
    avgDurationHours?: number;
    other?: number;
    rejected?: number;
    running?: number;
    started?: number;
    withdrawn?: number;
  };

  type resetPasswordParams = {
    id: number;
  };

  type resubmitReworkTaskParams = {
    id: number;
  };

  type retryAutomationJobParams = {
    id: number;
    jobId: number;
  };

  type retryOutboxParams = {
    id: string;
  };

  type revokeSessionParams = {
    id: string;
  };

  type ReworkResult = {
    businessNo?: string;
    firstTaskIds?: number[];
    formDataId?: number;
    instanceId?: number;
  };

  type ReworkTaskDto = {
    businessNo?: string;
    files?: MobileFileDto[];
    formCode?: string;
    formData?: JsonNode;
    formName?: string;
    instanceId?: number;
    processSnapshot?: JsonNode;
    schema?: JsonNode;
    taskId?: number;
  };

  type reworkTaskParams = {
    id: number;
  };

  type ReworkTaskRequest = {
    data?: JsonNode;
    files?: MobileFileRef[];
  };

  type RoleDto = {
    builtin?: boolean;
    code?: string;
    description?: string;
    enabled?: boolean;
    id?: number;
    name?: string;
    permissions?: PermissionGrantDto[];
    userCount?: number;
    version?: number;
  };

  type roleParams = {
    id: number;
  };

  type roles1Params = {
    keyword?: string;
  };

  type RoleWriteRequest = {
    code?: string;
    description?: string;
    enabled?: boolean;
    name?: string;
    permissions?: PermissionGrantWriteRequest[];
    version?: number;
  };

  type rowsParams = {
    id: number;
    versionId: number;
    limit?: number;
  };

  type RuntimePickerUserDto = {
    department?: string;
    displayName?: string;
    employeeNo?: string;
    id?: number;
  };

  type SaveBody = {
    code?: string;
    description?: string;
    id?: number;
    name?: string;
    schema?: Record<string, any>;
    settings?: Record<string, any>;
    status?: string;
  };

  type SaveProvider = {
    clientAuthMethod?: string;
    clientId?: string;
    clientSecret?: string;
    code?: string;
    displayName?: string;
    enabled?: boolean;
    issuerUri?: string;
    matchClaim?: string;
    matchField?: string;
    scopes?: string;
  };

  type saveReworkTaskParams = {
    id: number;
  };

  type SaveSettingsRequest = {
    agentId?: number;
    agentSecret?: string;
    companyId: number;
    corpId: string;
    jsSdkEnabled?: boolean;
    messageEnabled?: boolean;
    oauthEnabled?: boolean;
    scheduleEnabled?: boolean;
    scheduleMode?: string;
    scheduleTime?: string;
    secret?: string;
  };

  type selectedRolesParams = {
    ids: number[];
  };

  type selectedUsersParams = {
    ids: number[];
  };

  type SessionPayload = {
    accessToken?: string;
    authzVersion?: number;
    mobileBootstrap?: MobileBootstrapDto;
    permissions?: string[];
    user?: SessionUser;
  };

  type SessionUser = {
    authzVersion?: number;
    displayName?: string;
    email?: string;
    id?: number;
    permissions?: string[];
    roles?: string[];
    username?: string;
  };

  type setLoginAccessParams = {
    id: number;
  };

  type setRolesParams = {
    id: number;
  };

  type Settings = {
    enabled?: boolean;
    localTime?: LocalTime;
    retentionDays?: number;
    version?: number;
  };

  type settings2Params = {
    companyId: number;
  };

  type SettingsDto = {
    agentId?: number;
    agentSecretConfigured?: boolean;
    companyId?: number;
    corpId?: string;
    jsSdkEnabled?: boolean;
    latestJob?: JobDto;
    messageEnabled?: boolean;
    oauthEnabled?: boolean;
    scheduleEnabled?: boolean;
    scheduleMode?: string;
    scheduleTime?: string;
    secretConfigured?: boolean;
  };

  type SettingsWrite = {
    enabled?: boolean;
    localTime?: LocalTime;
    retentionDays?: number;
    version?: number;
  };

  type SourceDetail = {
    deletable?: boolean;
    deleteBlockedReason?: string;
    forms?: FormRef[];
    roleIds?: number[];
    source?: SourceSummary;
    userIds?: number[];
    versionUsage?: VersionUsage[];
    versions?: VersionView[];
  };

  type SourceSummary = {
    anyPublishedVersion?: boolean;
    code?: string;
    draftVersionId?: number;
    id?: number;
    inUseFormCount?: number;
    name?: string;
    publishedVersionId?: number;
    publishedVersionNo?: number;
    rowCount?: number;
    status?: string;
    updatedAt?: string;
    version?: number;
  };

  type SourceWrite = {
    code?: string;
    name?: string;
  };

  type SseEmitter = {
    timeout?: number;
  };

  type StartCmd = {
    companyId?: number;
    data?: Record<string, any>;
    formCode?: string;
    selfSelected?: Record<string, any>;
  };

  type StartJobRequest = {
    companyId: number;
    mode?: string;
  };

  type StartMobileInstanceRequest = {
    companyId?: number;
    data?: JsonNode;
    draftId?: number;
    files?: MobileFileRef[];
    formCode?: string;
    selfSelected?: Record<string, any>;
  };

  type Status = {
    error?: string;
    latest?: BackupFile;
    running?: boolean;
  };

  type submissionParams = {
    id: number;
  };

  type SubmitRequest = {
    companyId?: number;
    data?: Record<string, any>;
    draftId?: number;
    files?: MobileFileRef[];
    formCode?: string;
    status?: string;
  };

  type SubmitResult = {
    businessNo?: string;
    dataId?: number;
  };

  type Summary = {
    authzVersion?: number;
    code?: string;
    createdAt?: string;
    createdBy?: number;
    description?: string;
    id?: number;
    name?: string;
    status?: string;
    updatedAt?: string;
    version?: number;
  };

  type TaskEntity = {
    actionFormRevisionId?: number;
    approvalMode?: string;
    approvedAt?: string;
    approvedBy?: number;
    assigneeId?: number;
    branchId?: string;
    comment?: string;
    createdAt?: string;
    delegatedFrom?: number;
    id?: number;
    isAdditional?: boolean;
    nodeId?: string;
    nodeInstanceId?: number;
    nodeName?: string;
    operationKind?: string;
    parallelId?: string;
    parentTaskId?: number;
    procInstId?: number;
    readAt?: string;
    sequenceNo?: number;
    status?: string;
    taskType?: string;
    timeoutAt?: string;
    version?: number;
  };

  type TaskHistoryEntity = {
    action?: string;
    comment?: string;
    createdAt?: string;
    fromNodeId?: string;
    id?: number;
    operatorId?: number;
    procInstId?: number;
    taskId?: number;
    toNodeId?: string;
  };

  type taskParams = {
    id: number;
  };

  type tasksParams = {
    view?: string;
    page?: number;
    size?: number;
    keyword?: string;
    status?: string;
  };

  type terminateParams = {
    id: number;
  };

  type transferParams = {
    id: number;
  };

  type treeParams = {
    companyId: number;
  };

  type unbindParams = {
    providerId: number;
    bindingId: number;
  };

  type unpublishParams = {
    id: number;
    versionId: number;
  };

  type update1Params = {
    id: number;
  };

  type update2Params = {
    id: number;
  };

  type update3Params = {
    id: number;
  };

  type update4Params = {
    id: number;
  };

  type updateDraftParams = {
    id: number;
  };

  type updateParams = {
    id: number;
  };

  type uploadParams = {
    watermark?: boolean;
    watermarkText?: string;
  };

  type User = {
    authzVersion?: number;
    createdAt?: string;
    departmentLeader?: boolean;
    deptId?: number;
    displayName?: string;
    email?: string;
    employeeNo?: string;
    gender?: string;
    id?: number;
    managerDisplayName?: string;
    managerId?: number;
    phone?: string;
    position?: string;
    status?: string;
    username?: string;
    wecomDirectoryPresent?: boolean;
    wecomMapped?: boolean;
    wecomStatus?: number;
  };

  type user1Params = {
    id: number;
  };

  type userCandidates1Params = {
    page?: number;
    size?: number;
    keyword?: string;
    departmentId?: number;
  };

  type userCandidatesParams = {
    formId: number;
    page?: number;
    size?: number;
    keyword?: string;
    departmentId?: number;
  };

  type userParams = {
    id: number;
  };

  type UserRolePage = {
    page?: number;
    records?: UserRoleView[];
    size?: number;
    total?: number;
  };

  type UserRoleView = {
    authzVersion?: number;
    departmentId?: number;
    displayName?: string;
    employeeNo?: string;
    id?: number;
    roleIds?: number[];
    status?: string;
    username?: string;
  };

  type users1Params = {
    keyword?: string;
    deptId?: number;
    includeDescendants?: boolean;
    leaderOnly?: boolean;
    position?: string;
    userIds?: number[];
    scopeType?: string;
  };

  type users2Params = {
    keyword?: string;
    deptId?: number;
    includeDescendants?: boolean;
    leaderOnly?: boolean;
    position?: string;
    userIds?: number[];
    scopeType?: string;
  };

  type usersParams = {
    page?: number;
    size?: number;
    keyword?: string;
  };

  type VersionDiff = {
    added?: Record<string, any>[];
    addedTotal?: number;
    columnChanges?: string[];
    previousVersionNo?: number;
    removed?: Record<string, any>[];
    removedTotal?: number;
    truncated?: boolean;
    versionNo?: number;
  };

  type VersionUsage = {
    forms?: FormRef[];
    versionId?: number;
  };

  type VersionView = {
    columns?: string[];
    createdAt?: string;
    disabledAt?: string;
    id?: number;
    note?: string;
    originalName?: string;
    publishedAt?: string;
    publishedByName?: string;
    rowCount?: number;
    sha256?: string;
    sourceId?: number;
    status?: string;
    versionNo?: number;
  };

  type withdraw1Params = {
    id: number;
  };

  type withdraw2Params = {
    id: number;
  };

  type withdrawParams = {
    id: number;
  };

  type WorkflowPageProcessInstance = {
    page?: number;
    records?: ProcessInstance[];
    size?: number;
    total?: number;
  };

  type WorkflowPageTaskEntity = {
    page?: number;
    records?: TaskEntity[];
    size?: number;
    total?: number;
  };
}
