package com.netflix.vms.transformer.hollowinput;

import com.netflix.hollow.api.objects.delegate.HollowObjectDelegate;


@SuppressWarnings("all")
public interface MovieRatingsDelegate extends HollowObjectDelegate {

    public long getMovieId(int ordinal);

    public Long getMovieIdBoxed(int ordinal);

    public int getMediaOrdinal(int ordinal);

    public long getCertificationTypeId(int ordinal);

    public Long getCertificationTypeIdBoxed(int ordinal);

    public int getRatingReasonOrdinal(int ordinal);

    public MovieRatingsTypeAPI getTypeAPI();

}