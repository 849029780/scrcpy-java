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

    /**
     * FlowLayout normally vertically centers shorter components within a row.
     * Device tiles have different heights when portrait and landscape devices
     * share a row, so that behavior makes landscape tiles float halfway down
     * beside portrait tiles. Keep every tile anchored to the row's top edge.
     */
    @Override
    public void layoutContainer(Container target) {
        synchronized (target.getTreeLock()) {
            Insets insets = target.getInsets();
            int hgap = getHgap();
            int vgap = getVgap();
            int maxWidth = Math.max(1, target.getWidth() - insets.left - insets.right - hgap * 2);
            int y = insets.top + vgap;
            int rowWidth = 0;
            int rowHeight = 0;
            List<Component> row = new ArrayList<>();

            for (Component component : target.getComponents()) {
                if (!component.isVisible()) continue;
                Dimension size = component.getPreferredSize();
                int nextWidth = rowWidth == 0 ? size.width : rowWidth + hgap + size.width;
                if (!row.isEmpty() && nextWidth > maxWidth) {
                    layoutRow(row, rowWidth, rowHeight, maxWidth, insets.left + hgap, y, hgap);
                    y += rowHeight + vgap;
                    row.clear();
                    rowWidth = 0;
                    rowHeight = 0;
                }
                if (!row.isEmpty()) rowWidth += hgap;
                row.add(component);
                rowWidth += size.width;
                rowHeight = Math.max(rowHeight, size.height);
            }
            if (!row.isEmpty()) {
                layoutRow(row, rowWidth, rowHeight, maxWidth, insets.left + hgap, y, hgap);
            }
        }
    }

    private void layoutRow(List<Component> row, int rowWidth, int rowHeight,
                           int maxWidth, int startX, int y, int hgap) {
        int x = startX;
        if (getAlignment() == CENTER) {
            x += Math.max(0, (maxWidth - rowWidth) / 2);
        } else if (getAlignment() == RIGHT || getAlignment() == TRAILING) {
            x += Math.max(0, maxWidth - rowWidth);
        }
        for (Component component : row) {
            Dimension size = component.getPreferredSize();
            component.setBounds(x, y, size.width, size.height);
            x += size.width + hgap;
        }
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
