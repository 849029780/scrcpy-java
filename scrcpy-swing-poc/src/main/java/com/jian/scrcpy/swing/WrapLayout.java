package com.jian.scrcpy.swing;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** FlowLayout variant that reports the height required for the current width. */
final class WrapLayout extends FlowLayout {
    private static final long serialVersionUID = 1L;

    WrapLayout(int alignment, int hgap, int vgap) {
        super(alignment, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return layoutSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension size = layoutSize(target, false);
        size.width -= getHgap() + 1;
        return size;
    }

    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            int availableWidth = target.getSize().width;
            Container parent = target.getParent();
            if (availableWidth <= 0 && parent != null) availableWidth = parent.getSize().width;
            if (availableWidth <= 0) availableWidth = Integer.MAX_VALUE / 4;

            Insets insets = target.getInsets();
            int maxWidth = Math.max(1, availableWidth - insets.left - insets.right - getHgap() * 2);
            int rowWidth = 0;
            int rowHeight = 0;
            int totalHeight = insets.top + insets.bottom + getVgap() * 2;
            int totalWidth = 0;
            boolean hasRow = false;

            for (Component component : target.getComponents()) {
                if (!component.isVisible()) continue;
                Dimension d = preferred ? component.getPreferredSize() : component.getMinimumSize();
                if (rowWidth > 0 && rowWidth + getHgap() + d.width > maxWidth) {
                    totalHeight += rowHeight + getVgap();
                    totalWidth = Math.max(totalWidth, rowWidth);
                    rowWidth = 0;
                    rowHeight = 0;
                    hasRow = true;
                }
                if (rowWidth > 0) rowWidth += getHgap();
                rowWidth += d.width;
                rowHeight = Math.max(rowHeight, d.height);
            }
            if (rowHeight > 0) {
                totalHeight += rowHeight;
                totalWidth = Math.max(totalWidth, rowWidth);
                hasRow = true;
            }
            if (!hasRow) totalHeight += getVgap();
            return new Dimension(Math.min(availableWidth, totalWidth + insets.left + insets.right + getHgap() * 2),
                    totalHeight);
        }
    }
}
