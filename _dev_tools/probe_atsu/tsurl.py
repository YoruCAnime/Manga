"""Build Typesense search URLs exactly like the site's toSearchItem U() function. Prints NAME URL pairs."""
import sys
from urllib.parse import urlencode
Q_BY = 'title,englishTitle,otherNames,authors,acronyms'
INC = 'id,title,englishTitle,authors,poster,posterSmall,posterMedium,type,medium,isAdult,status,year,mbRating,popularity,dateAdded'
def url(q='', filt='', sort=None, page=1, per=40, include=INC):
    p = {'q': q or '*', 'query_by': Q_BY, 'query_by_weights': '4,3,2,2,1', 'num_typos': '4,3,2,1,0',
         'prefix': 'true,true,true,true,false', 'include_fields': include,
         'filter_by': ' && '.join(x for x in [filt, 'hidden:!=true'] if x), 'page': page, 'per_page': per}
    if q: p['infix'] = 'off,off,fallback,off,off'
    elif sort: p['sort_by'] = sort
    return 'https://atsu.moe/collections/manga/documents/search?' + urlencode(p)
