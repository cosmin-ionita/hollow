package com.netflix.vms.transformer.hollowinput;

import com.netflix.hollow.api.objects.delegate.HollowObjectDelegate;


@SuppressWarnings("all")
public interface ContractsDelegate extends HollowObjectDelegate {

    public long getMovieId(int ordinal);

    public Long getMovieIdBoxed(int ordinal);

    public int getCountryCodeOrdinal(int ordinal);

    public int getContractsOrdinal(int ordinal);

    public ContractsTypeAPI getTypeAPI();

}