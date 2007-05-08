#!/bin/bash

set -e

for i in {1..40}
do
	echo $i >&2
	perl $@ ../memtest.pl --data_min=$(( $i * 500 )) \
	                      --data_max=$(( $i * 500 + 1000)) \
			      --gets=300 \
			      --maxkey=300
done
