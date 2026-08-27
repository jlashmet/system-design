package com.systemdesign.ticketmaster.search.domain;

public interface EventSearchGateway {
    SearchPage search(SearchQuery query);
}
