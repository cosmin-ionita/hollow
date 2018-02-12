package com.netflix.vms.transformer.modules.countryspecific;

import static com.netflix.vms.transformer.util.OutputUtil.minValueToZero;
import static java.util.Arrays.asList;

import com.netflix.config.FastProperty;
import com.netflix.hollow.core.index.HollowPrimaryKeyIndex;
import com.netflix.vms.transformer.CycleConstants;
import com.netflix.vms.transformer.common.TransformerContext;
import com.netflix.vms.transformer.common.io.TransformerLogTag;
import com.netflix.vms.transformer.contract.ContractAssetType;
import com.netflix.vms.transformer.data.TransformedVideoData;
import com.netflix.vms.transformer.hollowinput.ContractHollow;
import com.netflix.vms.transformer.hollowinput.FlagsHollow;
import com.netflix.vms.transformer.hollowinput.RightsContractAssetHollow;
import com.netflix.vms.transformer.hollowinput.RightsContractPackageHollow;
import com.netflix.vms.transformer.hollowinput.RightsHollow;
import com.netflix.vms.transformer.hollowinput.RightsWindowContractHollow;
import com.netflix.vms.transformer.hollowinput.RightsWindowHollow;
import com.netflix.vms.transformer.hollowinput.StatusHollow;
import com.netflix.vms.transformer.hollowinput.VMSHollowInputAPI;
import com.netflix.vms.transformer.hollowinput.VideoGeneralHollow;
import com.netflix.vms.transformer.hollowoutput.CompleteVideoCountrySpecificData;
import com.netflix.vms.transformer.hollowoutput.CupKey;
import com.netflix.vms.transformer.hollowoutput.DateWindow;
import com.netflix.vms.transformer.hollowoutput.LinkedHashSetOfStrings;
import com.netflix.vms.transformer.hollowoutput.PackageData;
import com.netflix.vms.transformer.hollowoutput.Strings;
import com.netflix.vms.transformer.hollowoutput.VMSAvailabilityWindow;
import com.netflix.vms.transformer.hollowoutput.VideoContractInfo;
import com.netflix.vms.transformer.hollowoutput.VideoFormatDescriptor;
import com.netflix.vms.transformer.hollowoutput.VideoPackageInfo;
import com.netflix.vms.transformer.hollowoutput.WindowPackageContractInfo;
import com.netflix.vms.transformer.index.IndexSpec;
import com.netflix.vms.transformer.index.VMSTransformerIndexer;
import com.netflix.vms.transformer.modules.packages.PackageDataCollection;
import com.netflix.vms.transformer.util.OutputUtil;
import com.netflix.vms.transformer.util.VideoContractUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class VMSAvailabilityWindowModule {

    public static final long ONE_THOUSAND_YEARS = (1000L * 365L * 24L * 60L * 60L * 1000L);

    // @TODO: Should this be in TransformerConfig ???
    private static final FastProperty.BooleanProperty ENABLE_LOCALE_PROMOTION = new FastProperty.BooleanProperty("netflix.vms.transformer.enable.prepromotion.multilocale", false);

    private final VMSHollowInputAPI api;
    private final TransformerContext ctx;
    private final VMSTransformerIndexer indexer;
    private final HollowPrimaryKeyIndex videoGeneralIdx;

    private final com.netflix.vms.transformer.hollowoutput.Integer ZERO = new com.netflix.vms.transformer.hollowoutput.Integer(0);

    private final Strings DEFAULT_CUP_TOKEN = new Strings(CupKey.DEFAULT);
    private final LinkedHashSetOfStrings EMPTY_CUP_TOKENS;
    private final LinkedHashSetOfStrings DEFAULT_CUP_TOKENS;

    private TransformedVideoData transformedVideoData;

    private final WindowPackageContractInfoModule windowPackageContractInfoModule;
    private final MultilanguageCountryWindowFilter multilanguageCountryWindowFilter;

    public VMSAvailabilityWindowModule(VMSHollowInputAPI api, TransformerContext ctx, CycleConstants cycleConstants, VMSTransformerIndexer indexer) {
        this.api = api;
        this.ctx = ctx;
        this.indexer = indexer;
        this.videoGeneralIdx = indexer.getPrimaryKeyIndex(IndexSpec.VIDEO_GENERAL);

        this.windowPackageContractInfoModule = new WindowPackageContractInfoModule(api, indexer, ctx);
        this.multilanguageCountryWindowFilter = new MultilanguageCountryWindowFilter(cycleConstants);

        EMPTY_CUP_TOKENS = new LinkedHashSetOfStrings();
        EMPTY_CUP_TOKENS.ordinals = Collections.emptyList();

        DEFAULT_CUP_TOKENS = new LinkedHashSetOfStrings();
        DEFAULT_CUP_TOKENS.ordinals = Collections.singletonList(DEFAULT_CUP_TOKEN);
    }


    public void setTransformedVideoData(TransformedVideoData transformedVideoData) {
        this.transformedVideoData = transformedVideoData;
    }

    public List<VMSAvailabilityWindow> populateWindowData(Integer videoId, String country, CompleteVideoCountrySpecificData data, StatusHollow videoRights, CountrySpecificRollupValues rollup) {
        boolean isGoLive = isGoLive(videoRights);

        List<VMSAvailabilityWindow> windows = calculateWindowData(videoId, country, null, videoRights, rollup, isGoLive);

        data.availabilityWindows = windows;

        return windows;
    }

    List<VMSAvailabilityWindow> calculateWindowData(Integer videoId, String country, String locale, StatusHollow videoRights, CountrySpecificRollupValues rollup, boolean isGoLive) {
        List<VMSAvailabilityWindow> windows = null;

        RightsHollow rights = videoRights._getRights();
        if ((rollup.doShow() && rollup.wasShowEpisodeFound()) || (rollup.doSeason() && rollup.wasSeasonEpisodeFound())) {
            windows = populateRolledUpWindowData(videoId, rollup, rights, isGoLive, locale != null);
        } else {
            windows = populateEpisodeOrStandaloneWindowData(videoId, country, locale, rollup, isGoLive, rights, locale != null, videoRights);
            if (locale != null && windows.isEmpty() && isLanguageOverride(videoRights))
                windows = populateEpisodeOrStandaloneWindowData(videoId, country, null, rollup, isGoLive, rights, locale != null, videoRights);
        }
        return windows;
    }

    // todo need to split this out in a separate class. It's humongous. Not good.
    private List<VMSAvailabilityWindow> populateEpisodeOrStandaloneWindowData(Integer videoId, String country, String locale, CountrySpecificRollupValues rollup, boolean isGoLive, RightsHollow rights, boolean isMulticatalogRollup, StatusHollow statusHollow) {

        List<VMSAvailabilityWindow> availabilityWindows = new ArrayList<>();
        VMSAvailabilityWindow currentOrFirstFutureWindow = null;

        // for multi-language catalog processing only
        boolean isSubsDubsRequirementEnforced = ctx.getConfig().isSubsDubsRequirementEnforced();
        boolean mustHaveSubs = false;// local subtitles
        boolean mustHaveDubs = false;// local audio
        boolean mustHaveLocalizedData = false;// local synopsis

        FlagsHollow flags = statusHollow._getFlags();
        if (locale != null && flags != null) {
            Set<String> subsRequirement = new HashSet<>();
            Set<String> dubsRequirement = new HashSet<>();
            Set<String> localizedDataRequirement = new HashSet<>();

            if (flags._getSubsRequiredLanguages() != null)
                subsRequirement = flags._getSubsRequiredLanguages().stream().map(s -> s._getValue().toLowerCase()).collect(Collectors.toSet());
            if (flags._getDubsRequiredLanguages() != null)
                dubsRequirement = flags._getDubsRequiredLanguages().stream().map(s -> s._getValue().toLowerCase()).collect(Collectors.toSet());
            if (flags._getLocalizationRequiredLanguages() != null)
                localizedDataRequirement = flags._getLocalizationRequiredLanguages().stream().map(s -> s._getValue().toLowerCase()).collect(Collectors.toSet());

            String localeStr = locale.toLowerCase();
            if (subsRequirement.contains(localeStr)) mustHaveSubs = true;
            if (dubsRequirement.contains(localeStr)) mustHaveDubs = true;
            if (localizedDataRequirement.contains(localeStr)) mustHaveLocalizedData = true;
        }

        long minWindowStartDate = Long.MAX_VALUE;

        boolean currentOrFirstFutureWindowFoundLocalAudio = false;
        boolean currentOrFirstFutureWindowFoundLocalText = false;
        boolean isInWindow = false;

        int includedPackageDataCount = 0;
        int maxPackageId = 0;
        int contractIdForMaxPackageId = 0;

        List<RightsWindowHollow> sortedWindows = new ArrayList<>(rights._getWindows());
        Collections.sort(sortedWindows, RIGHTS_WINDOW_COMPARATOR);

        for (RightsWindowHollow window : sortedWindows) {

            int packageIdForWindow = 0;
            int thisWindowBundledAssetsGroupId = 0;

            boolean includedWindowPackageData = false;
            boolean thisWindowFoundLocalAudio = false;
            boolean thisWindowFoundLocalText = false;

            // create new window
            VMSAvailabilityWindow outputWindow = new VMSAvailabilityWindow();
            outputWindow.windowInfosByPackageId = new HashMap<>();
            outputWindow.startDate = OutputUtil.getRoundedDate(window._getStartDate());
            outputWindow.endDate = OutputUtil.getRoundedDate(window._getEndDate());
            if (window._getOnHold()) {
                outputWindow.startDate.val += ONE_THOUSAND_YEARS;
                outputWindow.endDate.val += ONE_THOUSAND_YEARS;
                outputWindow.onHold = true;
            }

            // collect all contractId for the window -> A window could have multiple contracts.
            List<Long> contractIds = new ArrayList<>();
            if (window._getContractIdsExt() != null)
                contractIds = window._getContractIdsExt().stream().map(c -> c._getContractId()).collect(Collectors.toList());

            // collect all contracts for the window
            List<RightsWindowContractHollow> windowContracts = new ArrayList<>();
            if (window._getContractIdsExt() != null)
                windowContracts = window._getContractIdsExt().stream().collect(Collectors.toList());


            // should use window data, check isGoLive flag, start-end dates and if video is in pre-promotion phase
            boolean shouldFilterOutWindowInfo = shouldFilterOutWindowInfo(videoId, country, isGoLive, contractIds, includedPackageDataCount, outputWindow.startDate.val, outputWindow.endDate.val);

            // if multi-locale catalog processing, then check if title is in pre-promo phase.
            boolean inPrePromotionPhase = false;
            if (!isGoLive && locale != null) {
                for (long contractId : contractIds) {
                    ContractHollow contractHollow = VideoContractUtil.getContract(api, indexer, videoId, country, contractId);
                    if (contractHollow != null && contractHollow._getPrePromotionDays() > 0) inPrePromotionPhase = true; // @TODO: This is a bit fishy - don't we need to check for contract window?
                }
                if (!isGoLive && inPrePromotionPhase)
                    ctx.getLogger().info(TransformerLogTag.PrePromotion, "Video={} country={} locale={} is in PrePromotion phase.", videoId, country, locale);
            }

            for (RightsWindowContractHollow windowContractHollow : windowContracts) {

                // get contract id from window contract and contract data from VideoContract feed.
                long contractId = windowContractHollow._getContractId();
                ContractHollow contractData = VideoContractUtil.getContract(api, indexer, videoId, country, contractId);
                boolean isAvailableForDownload = windowContractHollow._getDownload();

                // check if there are any assets & packages associated with this contract
                if (windowContractHollow._getPackageIdBoxed() != null || (windowContractHollow._getAssets() != null && !windowContractHollow._getAssets().isEmpty()) || (windowContractHollow._getPackages() != null && !windowContractHollow._getPackages().isEmpty())) {

                    // get contract assets rights & and check the availability of assets in the given locale
                    List<RightsContractAssetHollow> contractAssets = new ArrayList<>();
                    if (windowContractHollow._getAssets() != null)
                        contractAssets = windowContractHollow._getAssets().stream().collect(Collectors.toList());
                    long contractAssetAvailability = locale == null ? -1 : multilanguageCountryWindowFilter.contractAvailabilityForLanguage(locale, contractAssets);

                    // check if package list in the contract is not null or empty
                    if (windowContractHollow._getPackages() != null && !windowContractHollow._getPackages().isEmpty()) {

                        // collect all the contract packages -> A contract could have multiple packages
                        List<RightsContractPackageHollow> contractPackages = windowContractHollow._getPackages().stream().collect(Collectors.toList());
                        for (RightsContractPackageHollow contractPackageHollow : contractPackages) {

                            // create packageId, get packageDataCollection and packageData for the given package in the contract
                            com.netflix.vms.transformer.hollowoutput.Integer packageId = new com.netflix.vms.transformer.hollowoutput.Integer((int) contractPackageHollow._getPackageId());
                            PackageDataCollection packageDataCollection = getPackageDataCollection(videoId, contractPackageHollow._getPackageId());
                            PackageData packageData = null;
                            if (packageDataCollection != null)
                                packageData = packageDataCollection.getPackageData();


                            if (locale != null) {
                                long packageAvailability = multilanguageCountryWindowFilter.packageIsAvailableForLanguage(locale, packageData, contractAssetAvailability);

                                // multi-catalog processing -- make sure contract gives access to some existing asset understandable in this language
                                // if no assets and the the title is not in prePromotionPhase then skip this contract and no need to check subs/dubs requirement list too.
                                if (packageAvailability == 0 && !inPrePromotionPhase) {
                                    ctx.getLogger().info(TransformerLogTag.LocaleMerching, "Skipping contractId={} for videoId={} in country={} and locale={} because localized assets were not found in packgeId={}", contractId, videoId, country, locale, packageId);
                                    continue;
                                } else if (packageAvailability == 0 && inPrePromotionPhase) {
                                    // if feature (do not drop windows if assets are missing) enabled then do not skip the contract
                                    if (ENABLE_LOCALE_PROMOTION.get()) {
                                        ctx.getLogger().info(TransformerLogTag.PrePromotion, "Localized assets were not found for the videoId={} country={} and locale={}, not skipping contract since title is in pre-promo phase", videoId, country, locale);
                                    } else {
                                        // if feature not enabled, and assets are missing, skip the contract even if title is in Pre-promo phase
                                        continue;
                                    }
                                }

                                boolean considerPackageForLang = packageData == null ? true : packageData.isDefaultPackage;
                                if (!considerPackageForLang && contractPackages.size() == 1) {
                                    considerPackageForLang = true;
                                }

                                if (considerPackageForLang && (packageAvailability & ContractAssetType.AUDIO.getBitIdentifier()) != 0) {
                                    thisWindowFoundLocalAudio = true; // rollup.foundLocalAudio();
                                    if (currentOrFirstFutureWindow == outputWindow)
                                        currentOrFirstFutureWindowFoundLocalAudio = true;
                                }
                                if (considerPackageForLang && (packageAvailability & ContractAssetType.SUBTITLES.getBitIdentifier()) != 0) {
                                    thisWindowFoundLocalText = true; //rollup.foundLocalText();
                                    if (currentOrFirstFutureWindow == outputWindow)
                                        currentOrFirstFutureWindowFoundLocalText = true;
                                }

                                // only check subs/dubs requirement if feature is enabled and title is not in prePromotion
                                // quick note thisWindowFoundLocalText/thisWindowFoundLocalAudio flags are across contracts in a window. So if any contract has the assets, then it passes the requirement check.
                                if (isSubsDubsRequirementEnforced && !inPrePromotionPhase) {
                                    // if feature is enabled then check is given locale requires subs/dubs
                                    // and in case its a requirement and it is missing -> skip the current contract.
                                    if (mustHaveSubs && !thisWindowFoundLocalText) {
                                        ctx.getLogger().info(asList(TransformerLogTag.LocaleMerching, TransformerLogTag.LocaleMerchingMissingSubs), "[Missing Subs] Skipping contractId={}, packgeId={} for videoId={} in country={} and locale={}", contractId, packageId, videoId, country, locale);
                                        continue;
                                    }

                                    if (mustHaveDubs && !thisWindowFoundLocalAudio) {
                                        ctx.getLogger().info(asList(TransformerLogTag.LocaleMerching, TransformerLogTag.LocaleMerchingMissingDubs), "[Missing Dubs] Skipping contractId={}, packgeId={} for videoId={} in country={} and locale={}", contractId, packageId, videoId, country, locale);
                                        continue;
                                    }

                                    // @TODO check local synopsis. Can be deferred for now.
                                }
                            }

                            // get windowPackageContractInfo for the given packageId
                            WindowPackageContractInfo windowPackageContractInfo = outputWindow.windowInfosByPackageId.get(packageId);
                            if (windowPackageContractInfo != null) {

                                // For existing windowPackageContractInfo object
                                // check if this window data should be filtered
                                if (shouldFilterOutWindowInfo) {
                                    // if contract is greater than previous contract for this package id then update the windowContractInfo.contractId to use higher value for contractId
                                    if (contractId > windowPackageContractInfo.videoContractInfo.contractId) {
                                        // update contract id, since it is higher value.
                                        windowPackageContractInfo.videoContractInfo.contractId = (int) contractId;

                                        // if the current package id is the highest package id, then update the contract id for max package id.
                                        if (packageId.val == maxPackageId)
                                            contractIdForMaxPackageId = Math.max((int) contractId, contractIdForMaxPackageId);
                                        // if current package equals package id for this window, then update the window assets group id, max contract id for the current window.
                                        if (packageId.val == packageIdForWindow)
                                            thisWindowBundledAssetsGroupId = Math.max((int) contractId, thisWindowBundledAssetsGroupId);
                                    }

                                } else {

                                    // if existing windowPackageContractInfo is present and window data is not to be filtered, then do the following
                                    // 1. merge cup token values
                                    // 2. merge bcp47 code values
                                    // 3. clone the exiting contract info, and assign all new values
                                    // 4. update the map windowInfoByPackageId with current packageId with cloned windowPackageContractInfo
                                    // 5. the primary package id for the contractInfo is the max packageId for previous and current packageId
                                    // 6. the contract id for the contractInfo, is the max contractId from the previous and current contractId

                                    // merge cup tokens
                                    List<Strings> cupTokens = new ArrayList<>();
                                    Strings contractCupToken = contractData == null ? DEFAULT_CUP_TOKEN : new Strings(contractData._getCupToken()._getValue());
                                    if (windowPackageContractInfo.videoContractInfo.contractId > contractId) {
                                        cupTokens.addAll(windowPackageContractInfo.videoContractInfo.cupTokens.ordinals);
                                        if (!cupTokens.contains(contractCupToken))
                                            cupTokens.add(contractCupToken);
                                    } else {
                                        cupTokens.add(contractCupToken);
                                        for (Strings cupToken : windowPackageContractInfo.videoContractInfo.cupTokens.ordinals) {
                                            if (!cupToken.equals(contractCupToken))
                                                cupTokens.add(cupToken);
                                        }
                                    }
                                    // merge bcp47 codes
                                    Set<Strings> bcp47Codes = new HashSet<>(windowPackageContractInfo.videoContractInfo.assetBcp47Codes);
                                    for (RightsContractAssetHollow asset : contractAssets) {
                                        bcp47Codes.add(new Strings(asset._getBcp47Code()._getValue()));
                                    }
                                    // clone the contract info
                                    windowPackageContractInfo = windowPackageContractInfo.clone();
                                    windowPackageContractInfo.videoContractInfo = windowPackageContractInfo.videoContractInfo.clone();
                                    windowPackageContractInfo.videoContractInfo.cupTokens = new LinkedHashSetOfStrings(cupTokens);
                                    windowPackageContractInfo.videoContractInfo.assetBcp47Codes = bcp47Codes;
                                    windowPackageContractInfo.videoContractInfo.contractId = Math.max(windowPackageContractInfo.videoContractInfo.contractId, (int) contractId);
                                    windowPackageContractInfo.videoContractInfo.isAvailableForDownload = windowPackageContractInfo.videoContractInfo.isAvailableForDownload || isAvailableForDownload;
                                    windowPackageContractInfo.videoContractInfo.primaryPackageId = (int) Math.max(windowPackageContractInfo.videoContractInfo.primaryPackageId, contractPackageHollow._getPackageId());
                                    // update the package window package contract info
                                    outputWindow.windowInfosByPackageId.put(packageId, windowPackageContractInfo);

                                    // if the current package id is the highest package id, then update the contract id for max package id.
                                    if (packageId.val == maxPackageId)
                                        contractIdForMaxPackageId = Math.max((int) contractId, contractIdForMaxPackageId);
                                    // if current package equals package id for this window, then update the window assets group id, max contract id for the current window.
                                    if (packageId.val == packageIdForWindow)
                                        thisWindowBundledAssetsGroupId = Math.max((int) contractId, thisWindowBundledAssetsGroupId);
                                }
                            } else {

                                // if windowPackageContractInfo is not present in outputWindow.windowInfosByPackageId for the given packageId
                                // then create a new one

                                if (shouldFilterOutWindowInfo) {
                                    // if previously filtered window package contract info exists the update the contract id in that to use higher value
                                    WindowPackageContractInfo alreadyFilteredWindowPackageContractInfo = outputWindow.windowInfosByPackageId.get(ZERO);
                                    if (alreadyFilteredWindowPackageContractInfo != null) {
                                        if (alreadyFilteredWindowPackageContractInfo.videoContractInfo.contractId < (int) contractId)
                                            alreadyFilteredWindowPackageContractInfo.videoContractInfo.contractId = (int) contractId;
                                    } else {
                                        // if previously filtered window package contract info does not exists then create new filtered window package contract info.
                                        outputWindow.windowInfosByPackageId.put(ZERO, windowPackageContractInfoModule.buildFilteredWindowPackageContractInfo((int) contractId, videoId));
                                    }

                                    // current package id is the first one and window data needs to be filtered, then update contract id for max package id.
                                    if (maxPackageId == 0)
                                        contractIdForMaxPackageId = Math.max(contractIdForMaxPackageId, (int) contractId);

                                    // current package id is the fist one and window data needs to be filtered, then update the window assets group id, max contract id for the current window.
                                    if (packageIdForWindow == 0)
                                        thisWindowBundledAssetsGroupId = Math.max(thisWindowBundledAssetsGroupId, (int) contractId);
                                } else {

                                    // if windowPackageContractInfo is not present in outputWindow.windowInfosByPackageId for the given packageId and window data is not to be filtered
                                    includedWindowPackageData = true;

                                    if (packageData != null) {
                                        // package data is available
                                        windowPackageContractInfo = windowPackageContractInfoModule.buildWindowPackageContractInfo(packageData, windowContractHollow, contractData, country, isAvailableForDownload, packageDataCollection);
                                        outputWindow.windowInfosByPackageId.put(packageId, windowPackageContractInfo);
                                        boolean considerForPackageSelection = contractPackages == null ? true : packageData.isDefaultPackage;
                                        if (!considerForPackageSelection) {
                                            if (contractPackages.size() == 1)
                                                considerForPackageSelection = true;
                                        }
                                        if (considerForPackageSelection) {

                                            // if package is default package or the only package in contract packages list
                                            // update max package Id and contract id for max package id.
                                            if (packageData.id > maxPackageId) {
                                                maxPackageId = packageData.id;
                                                contractIdForMaxPackageId = (int) contractId;
                                            }

                                            // update max package Id for current window and max contract id for the current window.
                                            if (packageData.id > packageIdForWindow) {
                                                packageIdForWindow = packageData.id;
                                                thisWindowBundledAssetsGroupId = (int) contractId;
                                            }
                                        }

                                    } else {
                                        // package data not available -- use the contract only
                                        windowPackageContractInfo = windowPackageContractInfoModule.buildWindowPackageContractInfoWithoutPackage(packageId.val, windowContractHollow, contractData, videoId);
                                        outputWindow.windowInfosByPackageId.put(packageId, windowPackageContractInfo);

                                        // if fist package, then update contract id for the current window
                                        if (packageIdForWindow == 0)
                                            thisWindowBundledAssetsGroupId = Math.max((int) contractId, thisWindowBundledAssetsGroupId);
                                        // update contract id for the max package id
                                        if (maxPackageId == 0)
                                            contractIdForMaxPackageId = Math.max((int) contractId, contractIdForMaxPackageId);
                                    }
                                }

                                long windowEndDate = window._getEndDate();
                                long windowStartDate = window._getStartDate();
                                // if window is open then rollup the start window availability date and update the flag isInWindow
                                if (isGoLive && windowEndDate > ctx.getNowMillis() && windowStartDate < ctx.getNowMillis()) {
                                    rollup.newInWindowAvailabilityDate(windowStartDate);
                                    isInWindow = true;
                                }

                                // keep track if minimum window start date, only for windows where end date is greater than now
                                // also update currentOrFutureWindow and local audio/text values
                                if (windowEndDate > ctx.getNowMillis() && windowStartDate < minWindowStartDate) {
                                    minWindowStartDate = windowStartDate;
                                    currentOrFirstFutureWindow = outputWindow;
                                    currentOrFirstFutureWindowFoundLocalAudio = thisWindowFoundLocalAudio;
                                    currentOrFirstFutureWindowFoundLocalText = thisWindowFoundLocalText;
                                }
                            }

                        } // end of for loop for packages in contract


                    } else {
                        // package list is empty for the given contract -- use the contract only. Applicable only for non multi-locale country that is if locale is not passed

                        if (locale == null) {
                            // build info without package data, Use the assets and contract data though
                            WindowPackageContractInfo windowPackageContractInfo = windowPackageContractInfoModule.buildWindowPackageContractInfoWithoutPackage(0, windowContractHollow, contractData, videoId);
                            outputWindow.windowInfosByPackageId.put(ZERO, windowPackageContractInfo);

                            if (packageIdForWindow == 0)
                                thisWindowBundledAssetsGroupId = Math.max((int) contractId, thisWindowBundledAssetsGroupId);
                            if (maxPackageId == 0)
                                contractIdForMaxPackageId = Math.max((int) contractId, contractIdForMaxPackageId);
                        }
                    }
                } else {
                    if (locale == null) {
                        // if no assets and no associated package available then build info using just contract ID and video ID
                        outputWindow.windowInfosByPackageId.put(ZERO, windowPackageContractInfoModule.buildFilteredWindowPackageContractInfo((int) contractId, videoId));

                        if (maxPackageId == 0) {
                            contractIdForMaxPackageId = (int) contractId;
                            thisWindowBundledAssetsGroupId = (int) contractId;
                        }
                    }
                }

            } // end of for loop for iterating through contracts in a window

            // assign the highest contract Id recorded for the highest package Id in the current window
            outputWindow.bundledAssetsGroupId = thisWindowBundledAssetsGroupId;

            // if locale is not null and windowInfosByPackageId is empty then the code in if block is not executed.
            // Basically - Do not add: if all windows were filtered out for multicatalog country
            if (locale == null || !outputWindow.windowInfosByPackageId.isEmpty()) {
                availabilityWindows.add(outputWindow);

                // if evaluating episode and isGoLive is true, then roll up the windows to season.
                if (rollup.doEpisode()) {

                    if (isMulticatalogRollup)
                        rollup.windowFound(outputWindow.startDate.val, outputWindow.endDate.val);
                    if (isGoLive)
                        rollup.newSeasonWindow(outputWindow.startDate.val, outputWindow.endDate.val, outputWindow.onHold, rollup.getSeasonSequenceNumber());
                }
            }

            if (includedWindowPackageData)
                includedPackageDataCount++;

        } // end of for loop for iterating through windows for this video


        if (currentOrFirstFutureWindow != null) {

            // this basically gets the following data from max packageId across windows
            maxPackageId = Integer.MIN_VALUE;
            Set<Strings> assetBcp47CodesFromMaxPackageId = null;
            Set<VideoFormatDescriptor> videoFormatDescriptorsFromMaxPackageId = null;
            int prePromoDays = 0;
            boolean hasRollingEpisodes = false;
            boolean isAvailableForDownload = false;
            LinkedHashSetOfStrings cupTokens = null;

            for (Map.Entry<com.netflix.vms.transformer.hollowoutput.Integer, WindowPackageContractInfo> entry : currentOrFirstFutureWindow.windowInfosByPackageId.entrySet()) {
                VideoPackageInfo videoPackageInfo = entry.getValue().videoPackageInfo;
                boolean considerForPackageSelection = videoPackageInfo == null ? true : videoPackageInfo.isDefaultPackage;

                if (!considerForPackageSelection && currentOrFirstFutureWindow.windowInfosByPackageId.size() == 1) {
                    considerForPackageSelection = true;
                    ctx.getLogger().warn(TransformerLogTag.InteractivePackage, "Only one non-default package found for video={}, country={}", videoId, country);
                }

                if (considerForPackageSelection && (entry.getKey().val > maxPackageId)) {
                    maxPackageId = entry.getKey().val;
                    assetBcp47CodesFromMaxPackageId = entry.getValue().videoContractInfo.assetBcp47Codes;
                    videoFormatDescriptorsFromMaxPackageId = entry.getValue().videoPackageInfo.formats;
                    prePromoDays = minValueToZero(entry.getValue().videoContractInfo.prePromotionDays);
                    hasRollingEpisodes = entry.getValue().videoContractInfo.hasRollingEpisodes;
                    isAvailableForDownload = entry.getValue().videoContractInfo.isAvailableForDownload;
                    cupTokens = entry.getValue().videoContractInfo.cupTokens;
                }
            }

            // once all the data is available then rollup the asset bcp 47 codes and pre-promo days.

            rollup.newAssetBcp47Codes(assetBcp47CodesFromMaxPackageId);
            rollup.newPrePromoDays(prePromoDays);

            if (hasRollingEpisodes)
                rollup.foundRollingEpisodes();
            if (isAvailableForDownload)
                rollup.foundAvailableForDownload();

            if (isGoLive && isInWindow) {
                rollup.newVideoFormatDescriptors(videoFormatDescriptorsFromMaxPackageId);
                rollup.newCupTokens(cupTokens);
            }

            rollup.newEpisodeData(isGoLive, currentOrFirstFutureWindow.bundledAssetsGroupId);

            // for multi-catalog country, rollup the local audio and text values
            if (locale != null) {
                if (currentOrFirstFutureWindowFoundLocalAudio)
                    rollup.foundLocalAudio();
                if (currentOrFirstFutureWindowFoundLocalText)
                    rollup.foundLocalText();
            }

        } else if (locale == null) {
            // if no current or future window found, then do this, but why?
            rollup.newEpisodeData(isGoLive, contractIdForMaxPackageId);
            if (rollup.doEpisode())
                rollup.newPrePromoDays(0);
        }

        if (locale != null && (availabilityWindows == null || availabilityWindows.isEmpty()))
            ctx.getLogger().info(TransformerLogTag.LocaleMerching,"VideoId={} not merched in country={} locale={}", videoId, country, locale);

        return availabilityWindows;
    }

    // Return AvailabilityWindow from MediaData
    private List<VMSAvailabilityWindow> populateRolledUpWindowData(Integer videoId, CountrySpecificRollupValues
            rollup, RightsHollow rights, boolean isGoLive, boolean isMulticatalogRollup) {

        List<RightsWindowHollow> rightsWindowHollows = new ArrayList<>(rights._getWindows());
        if (!rightsWindowHollows.isEmpty()) {

            boolean isInWindow = false;
            int maxContractId = Integer.MIN_VALUE;

            Collections.sort(rightsWindowHollows, RIGHTS_WINDOW_COMPARATOR);
            List<VMSAvailabilityWindow> windowList = new ArrayList<>(rightsWindowHollows.size());

            for (RightsWindowHollow window : rightsWindowHollows) {
                long startDate = window._getStartDate();
                long endDate = window._getEndDate();
                boolean isOnHold = window._getOnHold();

                if (window._getOnHold()) {
                    startDate += ONE_THOUSAND_YEARS;
                    endDate += ONE_THOUSAND_YEARS;
                }

                if (startDate < ctx.getNowMillis() && endDate > ctx.getNowMillis())
                    isInWindow = true;

                if (window._getContractIdsExt() != null) {
                    for (RightsWindowContractHollow rightsWindowContract : window._getContractIdsExt()) {
                        if ((int) rightsWindowContract._getContractId() > maxContractId)
                            maxContractId = (int) rightsWindowContract._getContractId();
                    }
                }

                if (isMulticatalogRollup) {
                    DateWindow windowWithEpisodes = rollup.doShow() ? rollup.getValidShowWindow(startDate, endDate) : rollup.getValidSeasonWindow(startDate, endDate);

                    if (windowWithEpisodes != null) {
                        startDate = windowWithEpisodes.startDateTimestamp;
                        endDate = windowWithEpisodes.endDateTimestamp;
                    } else continue;
                }

                VMSAvailabilityWindow outputWindow = new VMSAvailabilityWindow();
                outputWindow.startDate = OutputUtil.getRoundedDate(startDate);
                outputWindow.endDate = OutputUtil.getRoundedDate(endDate);
                outputWindow.onHold = isOnHold;
                outputWindow.bundledAssetsGroupId = maxContractId; //rollup.getFirstEpisodeBundledAssetId();

                WindowPackageContractInfo outputContractInfo = createEmptyContractInfoForRollup(outputWindow);


                outputWindow.windowInfosByPackageId = new HashMap<>();
                outputWindow.windowInfosByPackageId.put(ZERO, outputContractInfo);

                outputContractInfo.videoContractInfo.assetBcp47Codes = rollup.getAssetBcp47Codes();
                outputContractInfo.videoContractInfo.prePromotionDays = rollup.getPrePromoDays();
                outputContractInfo.videoContractInfo.isDayAfterBroadcast = rollup.hasRollingEpisodes();
                outputContractInfo.videoContractInfo.hasRollingEpisodes = rollup.hasRollingEpisodes();
                outputContractInfo.videoContractInfo.isAvailableForDownload = rollup.isAvailableForDownload();
                outputContractInfo.videoContractInfo.postPromotionDays = 0;
                outputContractInfo.videoContractInfo.cupTokens = rollup.getCupTokens() != null ? rollup.getCupTokens() : DEFAULT_CUP_TOKENS;
                outputContractInfo.videoPackageInfo.formats = rollup.getVideoFormatDescriptors();

                if (rollup.getFirstEpisodeBundledAssetId() != 0) {
                    outputWindow.bundledAssetsGroupId = rollup.getFirstEpisodeBundledAssetId();
                    outputContractInfo.videoContractInfo.contractId = rollup.getFirstEpisodeBundledAssetId();
                }

                if (!(isGoLive && isInWindow))
                    outputContractInfo.videoPackageInfo.formats = Collections.emptySet();  ///TODO: This seems totally unnecessary.  We should remove this line after parity testing.

                int videoGeneralOrdinal = videoGeneralIdx.getMatchingOrdinal(Long.valueOf(videoId.intValue()));
                if (videoGeneralOrdinal != -1) {
                    VideoGeneralHollow general = api.getVideoGeneralHollow(videoGeneralOrdinal);
                    long runtime = general._getRuntime();
                    if (runtime != Long.MIN_VALUE)
                        outputContractInfo.videoPackageInfo.runtimeInSeconds = (int) runtime;
                }

                windowList.add(outputWindow);
            }

            return windowList;
        }

        return Collections.emptyList();
    }

    private WindowPackageContractInfo createEmptyContractInfoForRollup(VMSAvailabilityWindow outputWindow) {
        WindowPackageContractInfo contractInfo = new WindowPackageContractInfo();
        contractInfo.videoContractInfo = new VideoContractInfo();
        contractInfo.videoContractInfo.contractId = outputWindow.bundledAssetsGroupId;
        contractInfo.videoContractInfo.primaryPackageId = 0;
        contractInfo.videoContractInfo.assetBcp47Codes = Collections.emptySet();
        contractInfo.videoContractInfo.cupTokens = EMPTY_CUP_TOKENS;

        contractInfo.videoPackageInfo = WindowPackageContractInfoModule.newEmptyVideoPackageInfo();
        return contractInfo;
    }

    private PackageDataCollection getPackageDataCollection(Integer videoId, long packageId) {
        return transformedVideoData.getTransformedPackageData(videoId).getPackageDataCollection((int) packageId);
    }

    private static final long FUTURE_CUTOFF_IN_MILLIS = 360L * 24L * 60L * 60L * 1000L;

    /**
     * This function checks if the given window should be filtered.
     * - If the window end data is less than now, meaning window has already passed, then answer is yes.
     * - if isGoLive flag is false, then check if window data is needed. This is mainly for video (check all contracts) that are in pre-promotion stage or in days after broadcast.
     * - if isGoLive is true, then check if window is open, endDate is greater than now
     * - if isGoLive is true and endDate is > now, then check is window start is not in future.
     *
     * @param videoId
     * @param countryCode
     * @param isGoLive
     * @param contractIds
     * @param unfilteredCount
     * @param startDate
     * @param endDate
     * @return if window data should be filtered.
     */
    private boolean shouldFilterOutWindowInfo(long videoId, String countryCode, boolean isGoLive, Collection<Long> contractIds, int unfilteredCount, long startDate, long endDate) {
        if (endDate < ctx.getNowMillis())
            return true;

        if (!isGoLive) {
            boolean isWindowDataNeeded = false;
            for (Long contractId : contractIds) {
                ContractHollow contract = VideoContractUtil.getContract(api, indexer, videoId, countryCode, contractId);
                if (contract != null && (contract._getDayAfterBroadcast() || contract._getPrePromotionDays() > 0)) {
                    isWindowDataNeeded = true;
                }
            }

            if (!isWindowDataNeeded)
                return true;
        }

        if (unfilteredCount < 3 && endDate > ctx.getNowMillis())
            return false;

        if (startDate > ctx.getNowMillis() + FUTURE_CUTOFF_IN_MILLIS)
            return true;

        return false;
    }


    boolean isGoLive(StatusHollow status) {
        FlagsHollow flags = status._getFlags();
        return flags != null && flags._getGoLive();
    }

    boolean isLanguageOverride(StatusHollow status) {
        FlagsHollow flags = status._getFlags();
        return flags != null && flags._getLanguageOverride();
    }

    public void reset() {
        this.transformedVideoData = null;
        this.windowPackageContractInfoModule.reset();
    }

    private static final Comparator<RightsWindowHollow> RIGHTS_WINDOW_COMPARATOR = (o1, o2) -> {
        long t1 = o1._getOnHold() ? o1._getStartDate() + ONE_THOUSAND_YEARS : o1._getStartDate();
        long t2 = o2._getOnHold() ? o2._getStartDate() + ONE_THOUSAND_YEARS : o2._getStartDate();
        return t1 < t2 ? -1 : (t1 == t2 ? 0 : 1);
    };
}
